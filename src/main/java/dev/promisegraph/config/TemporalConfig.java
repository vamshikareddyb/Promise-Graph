package dev.promisegraph.config;

import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.extraction.Extractor;
import dev.promisegraph.gmail.GmailActivitiesImpl;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.handoff.HandoffScheduler;
import dev.promisegraph.notify.Notifier;
import dev.promisegraph.recovery.RecoveryPlanning;
import dev.promisegraph.recovery.RecoverySettings;
import dev.promisegraph.sim.Company;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.NegotiationStore;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.store.DeadLetterStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.DigestWorkflow;
import dev.promisegraph.temporal.HandoffActivitiesImpl;
import dev.promisegraph.temporal.LinkActivitiesImpl;
import dev.promisegraph.temporal.ModelActivitiesImpl;
import dev.promisegraph.temporal.NegotiationActivitiesImpl;
import dev.promisegraph.temporal.OpsActivitiesImpl;
import dev.promisegraph.temporal.ReconcileWorkflow;
import dev.promisegraph.temporal.PlanningActivitiesImpl;
import dev.promisegraph.temporal.PromiseActivitiesImpl;
import dev.promisegraph.temporal.RecoveryActivitiesImpl;
import dev.promisegraph.temporal.SituationLoader;
import dev.promisegraph.temporal.Workers;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal client, workers and schedules. */
@Configuration
public class TemporalConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

    /** 08:47 India time on weekdays (03:17 UTC). Off the hour so runs don't pile up at :00. */
    static final String DIGEST_CRON = "17 3 * * 1-5";

    /** Hourly, off the hour: the sweep that restarts promise workflows that died. */
    static final String RECONCILE_CRON = "41 * * * *";

    /** Temporal's own metrics (task latencies, failures, slots) go to the same Prometheus endpoint as ours. */
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs serviceStubs(PromiseGraphProperties props, ObjectProvider<MeterRegistry> registry) {
        WorkflowServiceStubsOptions.Builder options = WorkflowServiceStubsOptions.newBuilder().setTarget(props.temporal().address());
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            Scope scope = new RootScopeBuilder()
                    .reporter(new MicrometerClientStatsReporter(meters))
                    .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
            options.setMetricsScope(scope);
        }
        return WorkflowServiceStubs.newServiceStubs(options.build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, PromiseGraphProperties props) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(props.temporal().namespace()).build());
    }

    @Bean
    PromiseActivitiesImpl promiseActivities(Repository repo, LinkStore links, Notifier notifier, TimingPolicy policy,
            PromiseGraphProperties props, WorkflowClient client) {
        return new PromiseActivitiesImpl(repo, links, notifier, policy, props.confidenceThreshold(), client);
    }

    @Bean
    ModelActivitiesImpl modelActivities(Extractor extractor) {
        // The simulated company is the directory for now. A directory sync replaces this later.
        return new ModelActivitiesImpl(extractor, Company.directory());
    }

    @Bean
    SituationLoader situationLoader(Repository repo, LinkStore links, HandoffStore handoffs, Directory directory, RecoverySettings settings) {
        return new SituationLoader(repo, links, handoffs, directory, settings);
    }

    @Bean
    Workers.Activities activities(PromiseActivitiesImpl promises, ModelActivitiesImpl model, Repository repo, LinkStore links,
            HandoffStore handoffs, HandoffScheduler scheduler, HandoffPlanning.Planner planner, Directory directory,
            WorkflowClient client, SituationLoader situations, NegotiationStore negotiationStore,
            RecoveryPlanning.Planner recoveryPlanner, NegotiationProperties negotiation, DeadLetterStore deadLetters, AuditLog audit,
            TimingPolicy policy, PromiseGraphProperties props) {
        NegotiationActivitiesImpl.Policy policy = new NegotiationActivitiesImpl.Policy(negotiation.enabled(), negotiation.approvalHours(),
                negotiation.reminderHours(), negotiation.maxRounds(), negotiation.teamChecks());
        return new Workers.Activities(
                promises,
                new LinkActivitiesImpl(repo, links, promises, client),
                new HandoffActivitiesImpl(repo, handoffs, scheduler, promises, client),
                model,
                new PlanningActivitiesImpl(planner, repo, links, handoffs, directory),
                new NegotiationActivitiesImpl(situations, repo, negotiationStore, promises, client, policy, Clock.systemUTC()),
                new RecoveryActivitiesImpl(recoveryPlanner, situations),
                new OpsActivitiesImpl(repo, deadLetters, audit, client, policy, props.confidenceThreshold()));
    }

    @Bean(destroyMethod = "shutdown")
    WorkerFactory workerFactory(WorkflowClient client, Workers.Activities activities, PromiseGraphProperties props,
            ObjectProvider<GmailActivitiesImpl> gmailActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker main = Workers.register(factory::newWorker, activities, props.llmMaxConcurrency());
        // Present only when promisegraph.gmail.enabled=true. Registered before the factory starts.
        gmailActivities.ifAvailable(main::registerActivitiesImplementations);
        return factory;
    }

    /** Starts the workers and makes sure the schedules exist, unless this instance only serves HTTP or replays. */
    @Bean
    ApplicationRunner startWorkers(WorkerFactory factory, WorkflowServiceStubs stubs, PromiseGraphProperties props) {
        return args -> {
            if (!props.temporal().workerEnabled()) {
                log.info("worker disabled on this instance");
                return;
            }
            factory.start();
            ensureSchedules(stubs, props);
            log.info("worker running: tenant={} extractor={} store={}", props.tenant(), props.extractor(), props.store());
        };
    }

    static void ensureSchedules(WorkflowServiceStubs stubs, PromiseGraphProperties props) {
        ScheduleClient schedules = ScheduleClient.newInstance(stubs,
                ScheduleClientOptions.newBuilder().setNamespace(props.temporal().namespace()).build());
        ensure(schedules, "digest-" + props.tenant(), DigestWorkflow.class, "digest-run-" + props.tenant(), DIGEST_CRON, props);
        ensure(schedules, "reconcile-" + props.tenant(), ReconcileWorkflow.class, "reconcile-run-" + props.tenant(), RECONCILE_CRON, props);
    }

    private static void ensure(ScheduleClient schedules, String scheduleId, Class<?> workflow, String workflowId, String cron,
            PromiseGraphProperties props) {
        Schedule schedule = Schedule.newBuilder()
                .setAction(ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType(workflow)
                        .setArguments(props.tenant())
                        .setOptions(WorkflowOptions.newBuilder()
                                .setWorkflowId(workflowId)
                                .setTaskQueue(PromiseGraphProperties.TASK_QUEUE_MAIN)
                                .build())
                        .build())
                .setSpec(ScheduleSpec.newBuilder().setCronExpressions(List.of(cron)).build())
                .build();
        try {
            schedules.createSchedule(scheduleId, schedule, ScheduleOptions.newBuilder().build());
            log.info("created schedule {}", scheduleId);
        } catch (ScheduleAlreadyRunningException e) {
            // already there
        }
    }
}
