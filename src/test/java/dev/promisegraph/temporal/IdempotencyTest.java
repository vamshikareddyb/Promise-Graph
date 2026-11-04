package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.links.Link;
import dev.promisegraph.links.Ripple;
import dev.promisegraph.notify.Notification;
import dev.promisegraph.recovery.Move;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.recovery.RecoveryPlan;
import dev.promisegraph.temporal.Contracts.Action;
import dev.promisegraph.temporal.Contracts.DigestRequest;
import dev.promisegraph.temporal.Contracts.DispatchRequest;
import dev.promisegraph.temporal.Contracts.HandBackRequest;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import dev.promisegraph.temporal.Contracts.RecordRequest;
import dev.promisegraph.temporal.Contracts.ResolveRequest;
import dev.promisegraph.temporal.Contracts.RippleRequest;
import dev.promisegraph.temporal.Contracts.TransferRequest;
import dev.promisegraph.temporal.NegotiationContracts.ApplyStep;
import dev.promisegraph.temporal.NegotiationContracts.ReleaseRequest;
import dev.promisegraph.temporal.NegotiationContracts.ReserveRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The idempotency audit as a test: every activity with a side effect runs twice with the same
 * input, as Temporal does when a worker dies after an activity finished but before it reported
 * back. The second run must change nothing anyone can see: promise state and versions, events,
 * notifications, inbox items, links, absences, handoffs and negotiations.
 */
class IdempotencyTest {

    private TestWorkflowEnvironment env;
    private WorkflowClient client;
    private TestStack stack;
    private LocalDate day0;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        client = env.getWorkflowClient();
        stack = new TestStack(env);
        stack.register(env);
        env.start();
        day0 = LocalDate.ofInstant(Instant.ofEpochMilli(env.currentTimeMillis()), ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private String day(int n) {
        return day0.plusDays(n).toString();
    }

    private String now() {
        return Instant.ofEpochMilli(env.currentTimeMillis()).toString();
    }

    private Promise track(String id, String owner, String what, int dueDay) {
        Promise p = new Promise(id, "demo", owner, what, PromiseStatus.DETECTED, 0.9, now(), now(), null, day(dueDay), "", List.of(), 0);
        stack.repo.createPromise(p);
        PromiseWorkflow wf = client.newWorkflowStub(PromiseWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(Ids.promiseWorkflowId(id)).setTaskQueue(TASK_QUEUE_MAIN).build());
        WorkflowClient.start(wf::run, PromiseWorkflowInput.fresh(p, TimingPolicy.defaults(), 0.8));
        return p;
    }

    /** Everything a repeated activity could change, as one comparable value. */
    private Map<String, Object> snapshot() {
        env.sleep(Duration.ofSeconds(30)); // let workflows handle any signals
        Map<String, Object> s = new TreeMap<>();
        for (Promise p : stack.repo.promisesByStatus("demo", java.util.EnumSet.allOf(PromiseStatus.class))) {
            s.put("promise " + p.id(), p.status() + " " + p.owner() + " " + p.due() + " v" + p.version());
            s.put("events " + p.id(), stack.repo.events("demo", p.id()).size());
        }
        s.put("notifications", stack.recorded.count());
        List<String> inbox = new ArrayList<>();
        for (String r : stack.inbox.recipients("demo")) {
            stack.inbox.inbox("demo", r, false).forEach(i -> inbox.add(i.key() + " " + i.status()));
        }
        s.put("inbox", inbox.stream().sorted().toList());
        s.put("links", stack.links.links("demo").stream().map(l -> l.from() + ">" + l.to() + " " + l.status()).sorted().toList());
        s.put("absences", stack.handoffs.absences("demo").size());
        s.put("handoffs", stack.handoffs.handoffs("demo").stream().map(h -> h.id() + " " + h.status()).toList());
        s.put("negotiations", stack.negotiations.list("demo").stream().map(n -> n.id() + " " + n.status()).toList());
        return s;
    }

    /** Runs the call, snapshots, runs it again, and checks nothing changed and the answer is the same. */
    private <T> void twice(String name, Supplier<T> call) {
        T first = call.get();
        Map<String, Object> after = snapshot();
        T second = call.get();
        assertThat(snapshot()).as(name + ": state after a repeat").isEqualTo(after);
        assertThat(second).as(name + ": answer of a repeat").isEqualTo(first);
    }

    @Test
    void promiseActivities() {
        track("api", "neha", "ship the data export API", 10);
        env.sleep(Duration.ofHours(25));
        PromiseActivitiesImpl a = stack.promises;

        Commitment c = new Commitment(CommitmentKind.NEW, "m-deck", "ravi", "send the pricing deck", "I'll send the pricing deck by Friday.",
                0.9, "platform", null, day(4), null);
        twice("resolveCommitments", () -> a.resolveCommitments(new ResolveRequest("demo", List.of(c), now())).size());
        List<Action> actions = a.resolveCommitments(new ResolveRequest("demo", List.of(c), now()));
        twice("dispatchActions (start)", () -> a.dispatchActions(new DispatchRequest("demo", actions)));
        PromiseSignal slip = new PromiseSignal("slip", "m-slip", "neha", day(20), null, "slipping", "", null);
        twice("dispatchActions (signal)", () -> a.dispatchActions(new DispatchRequest("demo", List.of(new Action("signal", "api", null, slip)))));

        Promise api = stack.repo.getPromise("demo", "api").orElseThrow();
        twice("recordPromise", () -> {
            a.recordPromise(new RecordRequest(api, "restated", "test", Map.of()));
            return 0;
        });
        Notification n = Notification.about("demo", "neha", "hello", "k-hello", List.of("kept"), "api");
        // The answer differs by design (true, then false); the state must not.
        assertThat(a.sendNotification(n)).isTrue();
        Map<String, Object> once = snapshot();
        assertThat(a.sendNotification(n)).isFalse();
        assertThat(snapshot()).isEqualTo(once);
        twice("sendDigests", () -> {
            a.sendDigests(new DigestRequest("demo", now(), 3));
            return 0;
        });
    }

    @Test
    void claimingMessagesRunsInsideAnActivity() {
        TestActivityEnvironment activities = TestActivityEnvironment.newInstance();
        try {
            activities.registerActivitiesImplementations(stack.promises);
            PromiseActivities a = activities.newActivityStub(PromiseActivities.class);
            ExtractionInput input = new ExtractionInput("demo", List.of(
                    new Message("m1", "platform", "neha", "2026-11-02T10:05:00+05:30", "I'll ship the export API by 13 Nov.", "demo"),
                    new Message("m2", "product", "karan", "2026-11-02T10:05:00+05:30", "I'm on leave from 16 Nov to 27 Nov.", "demo")));
            var first = a.claimMessages(input);
            var second = a.claimMessages(input);
            assertThat(second).isEqualTo(first);
            assertThat(first.absences()).hasSize(1);
        } finally {
            activities.close();
        }
    }

    @Test
    void rippleAndHandoffActivities() {
        track("api", "neha", "ship the data export API", 10);
        track("job", "dev", "build the export ingestion job", 15);
        stack.links.addLink(new Link("demo", "api", "job", Link.Status.CONFIRMED, "test", "test", "test", now()));
        env.sleep(Duration.ofHours(25));

        RippleRequest ripple = new RippleRequest("demo", "api", day(10), day(20), "slipped");
        Ripple.Result result = stack.activities.links().computeRipple(ripple);
        twice("applyRipple", () -> stack.activities.links().applyRipple(ripple, result));

        Absence away = new Absence("demo", "dev", day(5), day(9), "leave", "test");
        twice("scheduleHandoffs", () -> {
            stack.activities.handoffs().scheduleHandoffs(List.of(away));
            return 0;
        });
        HandoffRecord record = new HandoffRecord("demo", "h-x", "dev", day(5), day(9), "leave", "active", "rules", List.of(), now());
        twice("saveHandoff", () -> {
            stack.activities.handoffs().saveHandoff(record);
            return 0;
        });
        twice("transfer", () -> stack.activities.handoffs().transfer(new TransferRequest("demo", "h-x", "out", Map.of("job", "isha"))));
        twice("handBack", () -> stack.activities.handoffs().handBack(new HandBackRequest("demo", "h-x", "dev", now(), Map.of("job", "isha"))));
        assertThat(stack.repo.getPromise("demo", "job")).get().extracting(Promise::owner).isEqualTo("dev");
    }

    @Test
    void negotiationActivities() {
        track("api", "neha", "ship the data export API", 10);
        track("job", "dev", "build the export ingestion job", 15);
        env.sleep(Duration.ofHours(25));
        NegotiationActivitiesImpl a = stack.negotiationActivities;
        Promise job = stack.repo.getPromise("demo", "job").orElseThrow();
        Promise api = stack.repo.getPromise("demo", "api").orElseThrow();

        RecoveryPlan plan = RecoveryPlan.draft("Add arjun", "x",
                List.of(Move.addHelper(api, "arjun", ""), Move.moveDate(job, day(19), "")), "test").withId("A");
        twice("reserve", () -> a.reserve(new ReserveRequest("demo", "n1", List.of(plan), day(20))));
        twice("release", () -> {
            a.release(new ReleaseRequest("demo", "n1", List.of()));
            return 0;
        });
        NegotiationRecord n = new NegotiationRecord("demo", "n1", "api", api.what(), "neha", day(10), day(20), "applying", 1, "",
                "rules", List.of(plan), List.of(), List.of("ravi", "meera"), Map.of(), "A", null, List.of(), now(), now());
        twice("saveNegotiation", () -> {
            a.saveNegotiation(n);
            return 0;
        });
        for (int i = 0; i < plan.moves().size(); i++) {
            ApplyStep step = new ApplyStep("demo", "n1", 1, "A", i, plan.moves().get(i), "ravi, meera");
            twice("applyMove " + i, () -> {
                a.applyMove(step);
                return 0;
            });
        }
        assertThat(stack.repo.getPromise("demo", "job")).get().extracting(Promise::due).isEqualTo(day(19));
        for (int i = plan.moves().size() - 1; i >= 0; i--) {
            ApplyStep step = new ApplyStep("demo", "n1", 1, "A", i, plan.moves().get(i), "ravi, meera");
            twice("undoMove " + i, () -> {
                a.undoMove(step);
                return 0;
            });
        }
        assertThat(stack.repo.getPromise("demo", "job")).get().extracting(Promise::due).isEqualTo(day(15));
        twice("supersede", () -> a.supersede("demo", "api", "n2"));
    }
}
