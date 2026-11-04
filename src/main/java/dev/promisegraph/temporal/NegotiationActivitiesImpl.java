package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Person;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.links.Ripple;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.recovery.Move;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.recovery.PlanVerifier;
import dev.promisegraph.recovery.RecoveryPlan;
import dev.promisegraph.recovery.Situation;
import dev.promisegraph.store.NegotiationStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import dev.promisegraph.temporal.NegotiationContracts.ApplyStep;
import dev.promisegraph.temporal.NegotiationContracts.NegotiationRequest;
import dev.promisegraph.temporal.NegotiationContracts.PrecheckRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReleaseRequest;
import dev.promisegraph.temporal.NegotiationContracts.Reservation;
import dev.promisegraph.temporal.NegotiationContracts.ReserveRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReserveResult;
import dev.promisegraph.temporal.NegotiationContracts.TeamAnswer;
import dev.promisegraph.temporal.NegotiationContracts.TeamInput;
import dev.promisegraph.temporal.NegotiationContracts.Trigger;
import dev.promisegraph.temporal.NegotiationContracts.Vote;
import io.micrometer.core.instrument.Metrics;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NegotiationActivitiesImpl implements NegotiationActivities {

    private static final Logger log = LoggerFactory.getLogger(NegotiationActivitiesImpl.class);

    /** The failure type that tells the saga a promise closed, so retrying can't help. */
    public static final String PROMISE_CLOSED = "PromiseClosed";

    /**
     * @param enabled false: ripples only warn, as in Phase 2
     * @param teamChecks ask each team's workflow before offering a plan that adds people
     */
    public record Policy(boolean enabled, int approvalHours, int reminderHours, int maxRounds, boolean teamChecks) {

        public static Policy defaults() {
            return new Policy(true, 72, 24, 3, true);
        }
    }

    private final SituationLoader loader;
    private final Repository repo;
    private final NegotiationStore store;
    private final PromiseActivities notifications;
    private final WorkflowClient client;
    private final Policy policy;
    private final Clock clock;

    /** @param notifications used for its idempotent {@code sendNotification} */
    public NegotiationActivitiesImpl(SituationLoader loader, Repository repo, NegotiationStore store, PromiseActivities notifications,
            WorkflowClient client, Policy policy, Clock clock) {
        this.loader = loader;
        this.repo = repo;
        this.store = store;
        this.notifications = notifications;
        this.client = client;
        this.policy = policy;
        this.clock = clock;
    }

    // ------------------------------------------------------------ starting and stopping

    @Override
    public Trigger shouldNegotiate(RippleRequest req, Ripple.Result result) {
        if (!policy.enabled()) {
            return Trigger.no("negotiation is turned off");
        }
        if (result.impacted().isEmpty()) {
            return Trigger.no("nothing depends on it");
        }
        if (req.newDue() == null) {
            return Trigger.no("there is no new date yet, so nothing to plan around");
        }
        Optional<Situation> loaded = loader.load("check", req, result, LocalDate.now(clock).toString());
        if (loaded.isEmpty()) {
            return Trigger.no("the promise no longer exists");
        }
        Situation s = loaded.get();
        int teams = s.teamsAffected().size();
        String reason;
        if (s.customerAtRisk()) {
            reason = "a promise made to a customer is at risk";
        } else if (teams >= loader.settings().minTeams()) {
            reason = "it affects " + teams + " teams";
        } else {
            return Trigger.no("it affects only " + teams + " team; the owners can sort it out");
        }
        return new Trigger(true, reason, s.source().owner(), s.source().what(), policy.approvalHours(), policy.reminderHours(),
                policy.maxRounds());
    }

    @Override
    public boolean startNegotiation(NegotiationRequest request) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(NegotiationContracts.workflowId(request.negotiationId()))
                .setTaskQueue(TASK_QUEUE_MAIN)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        try {
            WorkflowClient.start(client.newWorkflowStub(NegotiationWorkflow.class, options)::run, request);
            return true;
        } catch (WorkflowExecutionAlreadyStarted e) {
            return false;
        }
    }

    @Override
    public List<String> supersede(String tenant, String sourcePromiseId, String negotiationId) {
        List<String> stopped = new ArrayList<>();
        for (NegotiationRecord n : store.openFor(tenant, sourcePromiseId)) {
            if (n.id().equals(negotiationId)) {
                continue;
            }
            try {
                client.newUntypedWorkflowStub(NegotiationContracts.workflowId(n.id())).signal("vote",
                        Vote.stop("superseded", "system", "the promise slipped again", "supersede:" + negotiationId));
                stopped.add(n.id());
            } catch (WorkflowNotFoundException e) {
                log.info("negotiation {} already finished", n.id());
            }
        }
        return stopped;
    }

    @Override
    public void saveNegotiation(NegotiationRecord negotiation) {
        String before = store.get(negotiation.tenant(), negotiation.id()).map(NegotiationRecord::status).orElse(null);
        store.save(negotiation);
        if (!negotiation.status().equals(before)) {
            Metrics.counter("promisegraph.negotiations", "status", negotiation.status()).increment();
        }
    }

    // ------------------------------------------------------------ team capacity

    private Optional<String> teamOf(String person) {
        return loader.directory().person(person).map(Person::team);
    }

    private void ensureTeam(String tenant, String team) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(NegotiationContracts.teamWorkflowId(tenant, team))
                .setTaskQueue(TASK_QUEUE_MAIN)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build();
        try {
            WorkflowClient.start(client.newWorkflowStub(TeamWorkflow.class, options)::run,
                    new TeamInput(tenant, team, loader.settings().capacity(), List.of()));
        } catch (WorkflowExecutionAlreadyStarted e) {
            // running already
        }
    }

    @Override
    public ReserveResult reserve(ReserveRequest req) {
        List<String> accepted = new ArrayList<>();
        Map<String, String> refused = new LinkedHashMap<>();
        for (RecoveryPlan plan : req.plans()) {
            String refusal = policy.teamChecks() ? reservePlan(req, plan) : null;
            if (refusal == null) {
                accepted.add(plan.id());
            } else {
                refused.put(plan.id(), refusal);
            }
        }
        return new ReserveResult(accepted, refused);
    }

    /** @return null if every person the plan adds is held, else the team's reason */
    private String reservePlan(ReserveRequest req, RecoveryPlan plan) {
        for (Move m : plan.moves()) {
            if (!m.addsWork() || m.person() == null) {
                continue;
            }
            Optional<String> team = teamOf(m.person());
            if (team.isEmpty()) {
                continue; // outside the directory: no team to ask
            }
            ensureTeam(req.tenant(), team.get());
            TeamWorkflow teamWf = client.newWorkflowStub(TeamWorkflow.class, NegotiationContracts.teamWorkflowId(req.tenant(), team.get()));
            String key = NegotiationContracts.reservationKey(req.negotiationId(), m.promiseId(), m.person());
            try {
                TeamAnswer answer = teamWf.reserve(new Reservation(key, m.person(), req.negotiationId(), req.until()));
                if (!answer.ok()) {
                    return team.get() + ": " + answer.reason();
                }
            } catch (WorkflowException e) {
                return team.get() + " could not answer: " + e.getMessage();
            }
        }
        return null;
    }

    @Override
    public int release(ReleaseRequest req) {
        if (!policy.teamChecks()) {
            return 0;
        }
        Set<String> teams = new LinkedHashSet<>();
        loader.directory().people().forEach(p -> teams.add(p.team()));
        int released = 0;
        for (String team : teams) {
            try {
                released += client.newWorkflowStub(TeamWorkflow.class, NegotiationContracts.teamWorkflowId(req.tenant(), team)).release(req);
            } catch (WorkflowException e) {
                // no workflow for this team yet, so nothing is held there
            }
        }
        return released;
    }

    @Override
    public Map<String, Integer> teamWorkload(String tenant, String team) {
        return loader.teamWorkload(tenant, team);
    }

    // ------------------------------------------------------------ applying a plan

    @Override
    public List<String> precheck(PrecheckRequest req) {
        Optional<Situation> s = loader.load(req.negotiationId(), req.ripple(), req.result(), req.today());
        if (s.isEmpty()) {
            return List.of("the late promise no longer exists");
        }
        return PlanVerifier.verify(req.plan(), s.get()).problems();
    }

    private static ApplicationFailure closed(Move m) {
        return ApplicationFailure.newNonRetryableFailure("\"" + m.what() + "\" has closed since the plan was made", PROMISE_CLOSED);
    }

    private void signal(String promiseId, PromiseSignal s, Move m) {
        try {
            client.newUntypedWorkflowStub(Ids.promiseWorkflowId(promiseId)).signal(PromiseActivitiesImpl.PROMISE_SIGNAL, s);
        } catch (WorkflowNotFoundException e) {
            throw closed(m);
        }
    }

    private void tell(ApplyStep step, String recipient, String text, String phase) {
        notifications.sendNotification(Notification.about(step.tenant(), recipient, text,
                Ids.notificationKey(step.tenant(), "plan", step.sourceId(), recipient, phase), List.of(), step.move().promiseId()));
    }

    @Override
    public void applyMove(ApplyStep step) {
        Move m = step.move();
        Promise p = repo.getPromise(step.tenant(), m.promiseId()).orElseThrow(() -> closed(m));
        if (!p.stillOpen()) {
            throw closed(m);
        }
        String agreed = "The recovery plan agreed by " + step.approvedBy();
        switch (m.kind()) {
            case Move.MOVE_DATE -> {
                signal(p.id(), new PromiseSignal("confirm", step.sourceId(), "negotiation", m.toDue(), null, null, "", null), m);
                tell(step, p.owner(), agreed + " moves your promise to " + p.what() + " from " + m.fromDue() + " to " + m.toDue() + ".", "apply");
            }
            case Move.KEEP_DATE -> {
                signal(p.id(), PromiseSignal.of("confirm", step.sourceId(), "negotiation"), m);
                tell(step, p.owner(), agreed + " keeps your promise to " + p.what() + " on " + p.due() + ".", "apply");
            }
            case Move.DELIVER_PARTIAL -> {
                signal(p.id(), PromiseSignal.of("confirm", step.sourceId(), "negotiation"), m);
                tell(step, p.owner(), agreed + " keeps " + p.due() + " for your promise to " + p.what()
                        + ": deliver the part that doesn't need the late work by then, and the rest once it lands.", "apply");
            }
            case Move.ADD_HELPER -> {
                tell(step, m.person(), agreed + " asks you to help " + p.owner() + " with " + p.what()
                        + (p.due() == null ? "" : " (due " + p.due() + ")") + ".", "apply");
                tell(step, p.owner(), m.person() + " will help you with " + p.what() + ", as agreed in the recovery plan.", "apply-owner");
            }
            case Move.REASSIGN -> signal(p.id(), PromiseSignal.reassign(step.sourceId(), "negotiation", m.person()), m);
            default -> throw ApplicationFailure.newNonRetryableFailure("unknown move " + m.kind(), "UnknownMove");
        }
    }

    private void signalQuietly(String promiseId, PromiseSignal s) {
        try {
            client.newUntypedWorkflowStub(Ids.promiseWorkflowId(promiseId)).signal(PromiseActivitiesImpl.PROMISE_SIGNAL, s);
        } catch (WorkflowNotFoundException e) {
            log.info("promise {} closed; nothing to undo", promiseId);
        }
    }

    @Override
    public void undoMove(ApplyStep step) {
        Move m = step.move();
        String undo = step.sourceId() + ":undo";
        String calledOff = "The recovery plan was called off, so ";
        switch (m.kind()) {
            case Move.MOVE_DATE -> {
                if (m.fromDue() != null) {
                    signalQuietly(m.promiseId(), new PromiseSignal("confirm", undo, "negotiation", m.fromDue(), null, null, "", null));
                }
                tell(step, m.owner(), calledOff + "your promise to " + m.what() + " is back to " + (m.fromDue() == null ? "no date" : m.fromDue()) + ".", "undo");
            }
            case Move.KEEP_DATE, Move.DELIVER_PARTIAL ->
                    tell(step, m.owner(), calledOff + "ignore its note about " + m.what() + ".", "undo");
            case Move.ADD_HELPER -> tell(step, m.person(), calledOff + "you no longer need to help with " + m.what() + ".", "undo");
            case Move.REASSIGN -> signalQuietly(m.promiseId(), PromiseSignal.reassign(undo, "negotiation", m.owner()));
            default -> { }
        }
    }
}
