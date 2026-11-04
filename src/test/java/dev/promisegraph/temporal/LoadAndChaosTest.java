package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.FlakyRepository;
import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.recovery.RulePlanner;
import dev.promisegraph.sim.Company;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Many promises at once, with failures injected: storage writes that fail after succeeding, and
 * the worker killed in the middle of the work. Each promise follows one of three paths (kept,
 * slipped then kept, never answered and so missed), and at the end every promise must be in the
 * state its path implies, with every recorded version stored exactly once and no inbox item twice.
 *
 * <p>CI runs a few hundred promises on the time-skipping test server. {@code ./gradlew loadTest}
 * runs the same check against a real Temporal server at any size (see {@code LoadRunner}).
 */
class LoadAndChaosTest {

    private TestWorkflowEnvironment env;
    private WorkerFactory second;

    @AfterEach
    void tearDown() {
        if (second != null) {
            second.shutdownNow();
        }
        if (env != null) {
            env.close();
        }
    }

    private static String day(TestWorkflowEnvironment env, int n) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(env.currentTimeMillis()), ZoneOffset.UTC).plusDays(n).toString();
    }

    /** Starts {@code count} promises spread over the simulated company, due in 5 to 14 days. */
    static List<String> startPromises(TestWorkflowEnvironment env, TestStack stack, int count) {
        WorkflowClient client = env.getWorkflowClient();
        String now = Instant.ofEpochMilli(env.currentTimeMillis()).toString();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "load-" + i;
            String owner = Company.PEOPLE.get(i % Company.PEOPLE.size()).id();
            Promise p = new Promise(id, "demo", owner, "deliver item " + i, PromiseStatus.DETECTED, 0.9, now, now, null,
                    day(env, 5 + i % 10), "", List.of(), 0);
            stack.repo.createPromise(p);
            WorkflowClient.start(client.newWorkflowStub(PromiseWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId(Ids.promiseWorkflowId(id)).setTaskQueue(TASK_QUEUE_MAIN).build())::run,
                    PromiseWorkflowInput.fresh(p, TimingPolicy.defaults(), 0.8));
            ids.add(id);
        }
        return ids;
    }

    /** Path by index: 0 kept, 1 slipped a week then kept, 2 never answered. */
    static void signalPaths(TestWorkflowEnvironment env, List<String> ids, String phase) {
        WorkflowClient client = env.getWorkflowClient();
        for (int i = 0; i < ids.size(); i++) {
            var wf = client.newWorkflowStub(PromiseWorkflow.class, Ids.promiseWorkflowId(ids.get(i)));
            if (phase.equals("first") && i % 3 == 0) {
                wf.event(PromiseSignal.of("kept", "done-" + i, "owner"));
            } else if (phase.equals("first") && i % 3 == 1) {
                wf.event(new PromiseSignal("slip", "slip-" + i, "owner", day(env, 20), null, "a week late", "", null));
            } else if (phase.equals("second") && i % 3 == 1) {
                wf.event(PromiseSignal.of("kept", "done-" + i, "owner"));
            }
        }
    }

    /** Every promise ended where its path leads, every version is stored once, and no inbox item repeats. */
    static void checkOutcome(TestStack stack, List<String> ids) {
        Map<PromiseStatus, Integer> tally = new TreeMap<>();
        for (int i = 0; i < ids.size(); i++) {
            Promise p = stack.repo.getPromise("demo", ids.get(i)).orElseThrow();
            PromiseStatus expected = i % 3 == 2 ? PromiseStatus.BROKEN : PromiseStatus.KEPT;
            assertThat(p.status()).as(p.id()).isEqualTo(expected);
            var events = stack.repo.events("demo", p.id());
            assertThat(events).as("events of " + p.id()).hasSize(p.version());
            tally.merge(p.status(), 1, Integer::sum);
        }
        // Every notification reached the notifier exactly once, even when recording it failed afterwards.
        List<String> sent = stack.recorded.keys();
        assertThat(new HashSet<>(sent)).as("notifications sent once each").hasSize(sent.size());
        int inboxItems = 0;
        for (String person : stack.inbox.recipients("demo")) {
            inboxItems += stack.inbox.inbox("demo", person, false).size();
        }
        assertThat(inboxItems).isEqualTo(sent.size());
        System.out.println("load: " + ids.size() + " promises " + tally + ", " + sent.size() + " notifications");
    }

    @Test
    @Timeout(600)
    void hundredsOfPromisesWithStorageFailingAfterWrites() {
        env = TestWorkflowEnvironment.newInstance();
        FlakyRepository[] flaky = new FlakyRepository[1];
        TestStack stack = new TestStack(env, new HandoffPlanning.RulePlanner(), new RulePlanner(),
                NegotiationActivitiesImpl.Policy.defaults(), r -> flaky[0] = new FlakyRepository(r, 0.2, 42));
        stack.register(env);
        env.start();

        long started = System.nanoTime();
        List<String> ids = startPromises(env, stack, 300);
        env.sleep(Duration.ofHours(25)); // all activate
        signalPaths(env, ids, "first");
        env.sleep(Duration.ofDays(3));
        signalPaths(env, ids, "second");
        env.sleep(Duration.ofDays(30)); // unanswered ones pass their due date and are marked missed

        checkOutcome(stack, ids);
        assertThat(flaky[0].injected.get()).as("faults injected").isGreaterThan(100);
        System.out.printf("load: %d injected faults, %.1f s%n", flaky[0].injected.get(), (System.nanoTime() - started) / 1e9);
    }

    @Test
    @Timeout(600)
    void aKilledWorkerIsReplacedAndNothingIsLostOrDoneTwice() {
        env = TestWorkflowEnvironment.newInstance();
        TestStack stack = new TestStack(env);
        stack.register(env);
        env.start();

        List<String> ids = startPromises(env, stack, 120);
        env.sleep(Duration.ofHours(25));
        signalPaths(env, ids, "first");

        // Kill the worker while it is handling those signals, then bring up a new one.
        env.getWorkerFactory().shutdownNow();
        signalPaths(env, ids, "second"); // arrive while no worker is running
        second = WorkerFactory.newInstance(env.getWorkflowClient());
        Workers.register(second::newWorker, stack.activities, 2);
        second.start();

        env.sleep(Duration.ofDays(30));
        checkOutcome(stack, ids);
        assertThat(stack.repo.promisesByStatus("demo", EnumSet.of(PromiseStatus.ACTIVE, PromiseStatus.AT_RISK, PromiseStatus.DETECTED)))
                .isEmpty();
    }
}
