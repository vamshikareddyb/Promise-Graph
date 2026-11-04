package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.gmail.GmailSyncWorkflowImpl;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.links.Link;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Workflow code must stay deterministic: a running workflow is resumed by replaying its history
 * against the code deployed now. These tests guard that in two ways.
 *
 * <ul>
 *   <li>Every workflow type runs through a realistic story, and each history is replayed at once,
 *       which catches non-determinism inside the current code (for example iterating a HashMap).
 *   <li>Histories recorded from earlier versions live in {@code src/test/resources/histories}.
 *       Each must still replay on today's code; a change that breaks one needs
 *       {@code Workflow.getVersion}. See docs/versioning.md.
 * </ul>
 */
class ReplayTest {

    /** Every workflow implementation, so any history can be replayed. */
    static final Class<?>[] WORKFLOWS = {PromiseWorkflowImpl.class, ExtractionWorkflowImpl.class, DigestWorkflowImpl.class,
            RippleWorkflowImpl.class, HandoffWorkflowImpl.class, NegotiationWorkflowImpl.class, TeamWorkflowImpl.class,
            ReconcileWorkflowImpl.class, GmailSyncWorkflowImpl.class};

    static void replay(WorkflowExecutionHistory history) throws Exception {
        WorkflowReplayer.replayWorkflowExecution(history, WORKFLOWS[0], java.util.Arrays.copyOfRange(WORKFLOWS, 1, WORKFLOWS.length));
    }

    /** Runs one story touching every main workflow type and returns their histories by name. */
    static Map<String, WorkflowExecutionHistory> runStory() {
        TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
        try {
            WorkflowClient client = env.getWorkflowClient();
            TestStack stack = new TestStack(env);
            stack.register(env);
            env.start();
            LocalDate day0 = LocalDate.ofInstant(Instant.ofEpochMilli(env.currentTimeMillis()), ZoneOffset.UTC);
            String now = Instant.ofEpochMilli(env.currentTimeMillis()).toString();

            // An extraction batch: two promises from messages, one with a dependency phrase.
            String ts = Instant.ofEpochMilli(env.currentTimeMillis()).atOffset(ZoneOffset.UTC).toString();
            client.newWorkflowStub(ExtractionWorkflow.class, WorkflowOptions.newBuilder().setWorkflowId("extract-story")
                    .setTaskQueue(TASK_QUEUE_MAIN).build()).run(new ExtractionInput("demo", List.of(
                    new Message("s1", "platform", "ravi", ts, "I'll migrate the billing tables by " + day0.plusDays(9) + ".", "demo"))));

            // Promises for the slip, the negotiation and the handoff.
            for (String[] p : new String[][] {{"api", "neha", "ship the data export API", "10"}, {"job", "dev", "build the export ingestion job", "15"},
                    {"acme", "vikram", "send Acme the usage dashboard walkthrough", "17"}, {"faq", "karan", "write the launch FAQ for support", "12"},
                    {"miss", "omar", "close the onboarding ticket", "3"}}) {
                Promise promise = new Promise(p[0], "demo", p[1], p[2], PromiseStatus.DETECTED, 0.9, now, now, null,
                        day0.plusDays(Integer.parseInt(p[3])).toString(), "", List.of(), 0);
                stack.repo.createPromise(promise);
                WorkflowClient.start(client.newWorkflowStub(PromiseWorkflow.class, WorkflowOptions.newBuilder()
                        .setWorkflowId(Ids.promiseWorkflowId(p[0])).setTaskQueue(TASK_QUEUE_MAIN).build())::run,
                        PromiseWorkflowInput.fresh(promise, TimingPolicy.defaults(), 0.8));
            }
            stack.links.addLink(new Link("demo", "api", "job", Link.Status.CONFIRMED, "story", "story", "story", now));
            stack.links.addLink(new Link("demo", "job", "acme", Link.Status.CONFIRMED, "story", "story", "story", now));
            stack.scheduler.schedule(new Absence("demo", "karan", day0.plusDays(5).toString(), day0.plusDays(8).toString(), "leave", "story"));
            env.sleep(Duration.ofHours(25));

            // The API slips; the leads approve plan B; it is applied.
            client.newWorkflowStub(PromiseWorkflow.class, Ids.promiseWorkflowId("api"))
                    .event(new PromiseSignal("slip", "story-slip", "neha", day0.plusDays(24).toString(), null, "slipping", "", null));
            env.sleep(Duration.ofMinutes(5));
            NegotiationRecord n = stack.negotiations.list("demo").get(0);
            for (String lead : n.approvers()) {
                InboxItem ask = stack.inbox.inbox("demo", lead, true).stream().filter(i -> i.text().contains("Choose a recovery plan"))
                        .findFirst().orElseThrow();
                stack.inboxService.act("demo", ask.key(), new InboxService.ActionRequest("approve B", lead, null, null));
            }
            env.sleep(Duration.ofMinutes(5));

            // Karan's handoff: Nikhil accepts, covers, hands back. Omar's promise is missed.
            env.sleep(Duration.ofDays(1));
            InboxItem cover = stack.inbox.inbox("demo", "nikhil", true).stream().filter(i -> i.text().startsWith("Can you cover"))
                    .findFirst().orElseThrow();
            stack.inboxService.act("demo", cover.key(), new InboxService.ActionRequest("accept", "nikhil", null, null));
            env.sleep(Duration.ofDays(10));
            client.newWorkflowStub(DigestWorkflow.class, WorkflowOptions.newBuilder().setWorkflowId("digest-story")
                    .setTaskQueue(TASK_QUEUE_MAIN).build()).run("demo");
            client.newWorkflowStub(ReconcileWorkflow.class, WorkflowOptions.newBuilder().setWorkflowId("reconcile-story")
                    .setTaskQueue(TASK_QUEUE_MAIN).build()).run("demo");

            Map<String, String> ids = new LinkedHashMap<>();
            ids.put("extraction", "extract-story");
            ids.put("promise-slipped-and-replanned", Ids.promiseWorkflowId("api"));
            ids.put("promise-moved-by-plan", Ids.promiseWorkflowId("job"));
            ids.put("promise-missed", Ids.promiseWorkflowId("miss"));
            ids.put("promise-handed-over", Ids.promiseWorkflowId("faq"));
            ids.put("ripple", "ripple-" + n.id());
            ids.put("negotiation-applied", NegotiationContracts.workflowId(n.id()));
            ids.put("team", NegotiationContracts.teamWorkflowId("demo", "platform"));
            ids.put("handoff", "handoff-" + new Absence("demo", "karan", day0.plusDays(5).toString(), day0.plusDays(8).toString(), "leave", "story").handoffId());
            ids.put("digest", "digest-story");
            ids.put("reconcile", "reconcile-story");
            Map<String, WorkflowExecutionHistory> out = new LinkedHashMap<>();
            ids.forEach((name, id) -> out.put(name, client.fetchHistory(id)));
            assertThat(stack.negotiations.get("demo", n.id())).get().extracting(NegotiationRecord::status).isEqualTo("applied");
            return out;
        } finally {
            env.close();
        }
    }

    @Test
    @Timeout(300)
    void everyWorkflowTypeReplaysItsOwnHistory() throws Exception {
        Map<String, WorkflowExecutionHistory> histories = runStory();
        for (var e : histories.entrySet()) {
            assertThat(e.getValue().getEvents()).as(e.getKey()).isNotEmpty();
            replay(e.getValue());
        }
        // Run with PG_RECORD_HISTORIES=1 to print them, compressed, for src/test/resources/histories.
        if (System.getenv("PG_RECORD_HISTORIES") != null) {
            for (var e : histories.entrySet()) {
                System.out.println("HISTORY " + e.getKey() + " " + gzip(e.getValue().toJson(false)));
            }
        }
    }

    @Test
    void historiesFromEarlierVersionsStillReplay() throws Exception {
        List<Path> files = recorded();
        for (Path f : files) {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            try {
                replay(WorkflowExecutionHistory.fromJson(json));
            } catch (Exception | Error e) {
                throw new AssertionError("history " + f.getFileName() + " no longer replays: " + e, e);
            }
        }
        System.out.println("replayed " + files.size() + " recorded histories");
    }

    static List<Path> recorded() throws IOException, URISyntaxException {
        var url = ReplayTest.class.getResource("/histories");
        if (url == null) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(Paths.get(url.toURI()))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    static String gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /** Turns a printed history back into JSON for src/test/resources/histories. */
    static String gunzip(String base64) throws IOException {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(base64)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
