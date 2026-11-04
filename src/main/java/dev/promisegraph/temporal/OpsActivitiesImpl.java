package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.ops.AuditEntry;
import dev.promisegraph.ops.DeadLetter;
import dev.promisegraph.ops.ReconcileResult;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.store.DeadLetterStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.Metrics;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpsActivitiesImpl implements OpsActivities {

    private static final Logger log = LoggerFactory.getLogger(OpsActivitiesImpl.class);
    private static final EnumSet<PromiseStatus> OPEN = EnumSet.of(PromiseStatus.DETECTED, PromiseStatus.ACTIVE, PromiseStatus.AT_RISK);
    /** Statuses after which a promise workflow can safely be started again from the stored promise. */
    private static final EnumSet<WorkflowExecutionStatus> RESTARTABLE = EnumSet.of(
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED);

    private final Repository repo;
    private final DeadLetterStore deadLetters;
    private final AuditLog audit;
    private final WorkflowClient client;
    private final TimingPolicy policy;
    private final double threshold;

    public OpsActivitiesImpl(Repository repo, DeadLetterStore deadLetters, AuditLog audit, WorkflowClient client, TimingPolicy policy,
            double threshold) {
        this.repo = repo;
        this.deadLetters = deadLetters;
        this.audit = audit;
        this.client = client;
        this.policy = policy;
        this.threshold = threshold;
    }

    @Override
    public void recordDeadLetter(DeadLetter d) {
        if (deadLetters.add(d)) {
            Metrics.counter("promisegraph.dead_letters", "kind", d.kind()).increment();
            log.warn("dead letter {} ({}): {}", d.id(), d.kind(), d.error());
        }
    }

    /** Empty when no workflow with this ID has ever run. */
    private Optional<WorkflowExecutionStatus> status(String workflowId) {
        try {
            var response = client.getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(
                    DescribeWorkflowExecutionRequest.newBuilder()
                            .setNamespace(client.getOptions().getNamespace())
                            .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                            .build());
            return Optional.of(response.getWorkflowExecutionInfo().getStatus());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public ReconcileResult reconcile(String tenant, String now) {
        List<String> restarted = new ArrayList<>();
        List<String> outOfSync = new ArrayList<>();
        int checked = 0;
        for (Promise p : repo.promisesByStatus(tenant, OPEN)) {
            checked++;
            String workflowId = Ids.promiseWorkflowId(p.id());
            Optional<WorkflowExecutionStatus> s = status(workflowId);
            if (s.isPresent() && s.get() == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING) {
                continue;
            }
            if (s.isPresent() && !RESTARTABLE.contains(s.get())) {
                outOfSync.add(p.id());
                recordDeadLetter(new DeadLetter(tenant, "out_of_sync:" + p.id(), "out_of_sync", workflowId,
                        "The workflow finished (" + s.get().name().replace("WORKFLOW_EXECUTION_STATUS_", "").toLowerCase()
                                + ") but the store still shows \"" + p.what() + "\" as " + p.status().dbValue() + ".",
                        List.of(), now, "open"));
                continue;
            }
            // Never started (a crash between saving and starting) or stopped abnormally: start again from the store.
            boolean neverStarted = s.isEmpty() && p.status() == PromiseStatus.DETECTED && p.version() == 0;
            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(TASK_QUEUE_MAIN)
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                    .build();
            try {
                client.newUntypedWorkflowStub(PromiseActivitiesImpl.PROMISE_WORKFLOW, options)
                        .start(new PromiseWorkflowInput(p, policy, threshold, !neverStarted, List.of(), List.of()));
            } catch (WorkflowExecutionAlreadyStarted e) {
                continue; // started meanwhile, e.g. by a parallel sweep
            }
            restarted.add(p.id());
            Metrics.counter("promisegraph.reconcile.restarted").increment();
            audit.record(new AuditEntry(tenant, now, "system", "reconcile:restart", p.id(),
                    s.map(x -> "workflow was " + x.name().replace("WORKFLOW_EXECUTION_STATUS_", "").toLowerCase()).orElse("workflow was never started")));
            deadLetters.add(new DeadLetter(tenant, "restarted:" + p.id() + ":" + now, "restarted", workflowId,
                    s.map(x -> "workflow was " + x.name().replace("WORKFLOW_EXECUTION_STATUS_", "").toLowerCase()).orElse("workflow was never started"),
                    List.of(), now, "auto_fixed"));
        }
        if (!restarted.isEmpty() || !outOfSync.isEmpty()) {
            log.warn("reconcile: checked {}, restarted {}, out of sync {}", checked, restarted, outOfSync);
        }
        return new ReconcileResult(checked, restarted, outOfSync);
    }
}
