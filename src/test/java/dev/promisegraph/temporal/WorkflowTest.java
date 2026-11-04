package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.RecordingNotifier;
import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseEvent;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.store.InMemoryRepository;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Workflow tests on Temporal's in-process test server with time skipping: timers that would
 * take days complete instantly, so whole promise lifecycles run in seconds.
 */
class WorkflowTest {

    private TestWorkflowEnvironment env;
    private WorkflowClient client;
    private InMemoryRepository repo;
    private RecordingNotifier notifier;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        client = env.getWorkflowClient();
        TestStack stack = new TestStack(env);
        repo = stack.repo;
        notifier = stack.recorded;
        stack.register(env);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private Instant now() {
        return Instant.ofEpochMilli(env.currentTimeMillis());
    }

    private Promise promise(double confidence, Integer dueInDays) {
        Instant now = now();
        String due = dueInDays == null ? null : LocalDate.ofInstant(now.plus(Duration.ofDays(dueInDays)), ZoneOffset.UTC).toString();
        Promise p = new Promise("p-" + UUID.randomUUID().toString().substring(0, 8), "demo", "neha", "send the pricing deck",
                PromiseStatus.DETECTED, confidence, now.toString(), now.toString(), null, due, "", List.of(), 0);
        repo.createPromise(p);
        return p;
    }

    private PromiseWorkflow start(Promise p) {
        PromiseWorkflow wf = client.newWorkflowStub(PromiseWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(Ids.promiseWorkflowId(p.id())).setTaskQueue(TASK_QUEUE_MAIN).build());
        WorkflowClient.start(wf::run, PromiseWorkflowInput.fresh(p, TimingPolicy.defaults(), 0.8));
        return wf;
    }

    private static PromiseStatus result(PromiseWorkflow wf) {
        return WorkflowStub.fromTyped(wf).getResult(PromiseStatus.class);
    }

    @Test
    void confidentPromiseActivatesAndIsKept() {
        Promise p = promise(0.9, 5);
        PromiseWorkflow wf = start(p);
        env.sleep(Duration.ofHours(25));
        assertThat(wf.state().status()).isEqualTo(PromiseStatus.ACTIVE);

        wf.event(PromiseSignal.of("kept", "msg-done", "neha"));
        assertThat(result(wf)).isEqualTo(PromiseStatus.KEPT);
        assertThat(notifier.textsFor("neha")).anyMatch(t -> t.startsWith("Tracking your promise"));
        assertThat(repo.getPromise("demo", p.id())).get().extracting(Promise::status).isEqualTo(PromiseStatus.KEPT);
    }

    @Test
    void unconfirmedLowConfidencePromiseIsDismissed() {
        PromiseWorkflow wf = start(promise(0.6, null));
        assertThat(result(wf)).isEqualTo(PromiseStatus.DISMISSED);
        assertThat(notifier.textsFor("neha")).anyMatch(t -> t.startsWith("Did you promise"));
    }

    @Test
    void unansweredPromiseGetsRemindersThenBreaks() {
        PromiseWorkflow wf = start(promise(0.9, 5));
        assertThat(result(wf)).isEqualTo(PromiseStatus.BROKEN);
        assertThat(notifier.countStartingWith("neha", "Reminder:")).isEqualTo(2);
        assertThat(notifier.countStartingWith("neha", "Due today")).isEqualTo(1);
        assertThat(notifier.countStartingWith("neha", "Marked as missed")).isEqualTo(1);
    }

    @Test
    void slipThenConfirmThenKeptWithDuplicatesIgnored() {
        Promise p = promise(0.9, 10);
        PromiseWorkflow wf = start(p);
        env.sleep(Duration.ofHours(25));

        String newDue = LocalDate.ofInstant(now().plus(Duration.ofDays(20)), ZoneOffset.UTC).toString();
        PromiseSignal slip = new PromiseSignal("slip", "msg-slip", "neha", newDue, null, "will slip", "", null);
        wf.event(slip);
        wf.event(slip); // duplicate delivery
        env.sleep(Duration.ofMinutes(1));
        assertThat(wf.state().status()).isEqualTo(PromiseStatus.AT_RISK);
        assertThat(wf.state().due()).isEqualTo(newDue);
        assertThat(notifier.countStartingWith("neha", "Noted:")).isEqualTo(1);

        wf.event(PromiseSignal.of("confirm", "click-1", "neha"));
        env.sleep(Duration.ofMinutes(1));
        assertThat(wf.state().status()).isEqualTo(PromiseStatus.ACTIVE);

        wf.event(PromiseSignal.of("kept", "msg-done", "neha"));
        assertThat(result(wf)).isEqualTo(PromiseStatus.KEPT);
        List<String> kinds = repo.events("demo", p.id()).stream().map(PromiseEvent::kind).toList();
        assertThat(kinds).containsOnlyOnce("slipped").contains("confirmed").last().isEqualTo("kept");
    }

    @Test
    void extractionPipelineCreatesAndClosesPromises() {
        String ts = now().atOffset(ZoneOffset.UTC).toString();
        List<Message> batch1 = List.of(
                new Message("m1", "platform", "arjun", ts, "I'll share the export field list with analytics by Friday.", "demo"),
                new Message("m2", "platform", "ravi", ts, "Can someone review the API spec?", "demo"));
        extract("extract-1", batch1);
        List<Promise> open = repo.openPromises("demo", List.of("arjun"));
        assertThat(open).singleElement().extracting(Promise::what).isEqualTo("share the export field list with analytics");

        // Delivering the same batch again under a new ID must not create anything new.
        extract("extract-1-again", batch1);
        assertThat(repo.openPromises("demo", List.of("arjun"))).hasSize(1);

        extract("extract-2", List.of(new Message("m3", "platform", "arjun", ts,
                "Done: shared the export field list with analytics.", "demo")));
        WorkflowStub promiseWf = client.newUntypedWorkflowStub(Ids.promiseWorkflowId(open.get(0).id()));
        assertThat(promiseWf.getResult(PromiseStatus.class)).isEqualTo(PromiseStatus.KEPT);
    }

    private void extract(String workflowId, List<Message> messages) {
        ExtractionWorkflow wf = client.newWorkflowStub(ExtractionWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(TASK_QUEUE_MAIN).build());
        wf.run(new ExtractionInput("demo", messages));
    }
}
