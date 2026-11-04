package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.HandoffRecord;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.links.Link;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Links, ripple, the inbox and handoffs, end to end on the time-skipping test server. */
class Phase2WorkflowTest {

    private TestWorkflowEnvironment env;
    private WorkflowClient client;
    private TestStack stack;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        client = env.getWorkflowClient();
        stack = new TestStack(env);
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

    private String inDays(int days) {
        return LocalDate.ofInstant(now().plus(Duration.ofDays(days)), ZoneOffset.UTC).toString();
    }

    private Promise track(String id, String owner, String what, int dueInDays) {
        Promise p = new Promise(id, "demo", owner, what, PromiseStatus.DETECTED, 0.9, now().toString(), now().toString(),
                null, inDays(dueInDays), "", List.of(), 0);
        stack.repo.createPromise(p);
        PromiseWorkflow wf = client.newWorkflowStub(PromiseWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(Ids.promiseWorkflowId(id)).setTaskQueue(TASK_QUEUE_MAIN).build());
        WorkflowClient.start(wf::run, PromiseWorkflowInput.fresh(p, TimingPolicy.defaults(), 0.8));
        return p;
    }

    private PromiseWorkflow promiseWf(String id) {
        return client.newWorkflowStub(PromiseWorkflow.class, Ids.promiseWorkflowId(id));
    }

    private InboxItem openItem(String person, String containing) {
        return stack.inbox.inbox("demo", person, true).stream().filter(i -> i.text().contains(containing)).findFirst()
                .orElseThrow(() -> new AssertionError("no open inbox item for " + person + " containing: " + containing
                        + "; inbox: " + stack.inbox.inbox("demo", person, false).stream().map(InboxItem::text).toList()));
    }

    private void click(InboxItem item, String action, String by, String due) {
        stack.inboxService.act("demo", item.key(), new InboxService.ActionRequest(action, by, due, null));
    }

    @Test
    void slipRipplesToDependentsWhoCanKeepTheirDate() {
        track("api", "neha", "ship the data export API", 10);
        track("job", "dev", "build the export ingestion job", 15);
        stack.links.addLink(new Link("demo", "api", "job", Link.Status.CONFIRMED, "once the API is live", "test", "test", now().toString()));
        env.sleep(Duration.ofHours(25)); // both auto-activate

        String newDue = inDays(23);
        promiseWf("api").event(new PromiseSignal("slip", "msg-slip", "neha", newDue, null, "The API will slip", "", null));
        env.sleep(Duration.ofMinutes(5));

        assertThat(promiseWf("job").state().status()).isEqualTo(PromiseStatus.AT_RISK);
        InboxItem warning = openItem("dev", "neha's promise to ship the data export API is now expected " + newDue);
        assertThat(warning.actions()).containsExactly("keep date", "new date");
        assertThat(openItem("neha", "affects 1 promise").text()).contains("build the export ingestion job");

        click(warning, "keep date", "dev", null);
        env.sleep(Duration.ofMinutes(1));
        assertThat(promiseWf("job").state().status()).isEqualTo(PromiseStatus.ACTIVE);
        assertThat(stack.repo.events("demo", "job")).extracting(e -> e.kind()).contains("upstream_slipped", "confirmed");
    }

    @Test
    void messagesSuggestLinksAndStartHandoffs() {
        String ts = now().atOffset(ZoneOffset.UTC).toString();
        String start = inDays(6);
        String end = inDays(10);
        List<Message> batch = List.of(
                new Message("m1", "platform", "neha", ts, "I'll ship the data export API by " + inDays(10) + ".", "demo"),
                new Message("m2", "analytics", "dev", ts, "I'll build the export ingestion job by " + inDays(15) + ", once the API is live.", "demo"),
                new Message("m3", "product", "karan", ts, "Heads up, I'm on leave from " + start + " to " + end + ".", "demo"));
        client.newWorkflowStub(ExtractionWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("extract-p2").setTaskQueue(TASK_QUEUE_MAIN).build())
                .run(new ExtractionInput("demo", batch));

        // A suggested link waits for dev to confirm it.
        Link suggested = stack.links.links("demo").get(0);
        assertThat(suggested.status()).isEqualTo(Link.Status.SUGGESTED);
        assertThat(stack.repo.getPromise("demo", suggested.from())).get().extracting(Promise::what).isEqualTo("ship the data export API");
        click(openItem("dev", "depend on neha's promise"), "confirm link", "dev", null);
        assertThat(stack.links.getLink("demo", suggested.from(), suggested.to())).get()
                .extracting(Link::status).isEqualTo(Link.Status.CONFIRMED);

        // The leave message scheduled a handoff.
        env.sleep(Duration.ofMinutes(1));
        assertThat(stack.handoffs.absences("demo")).singleElement().satisfies(a -> {
            assertThat(a.person()).isEqualTo("karan");
            assertThat(a.startsOn()).isEqualTo(start);
        });
        assertThat(stack.handoffs.getHandoff("demo", "demo-karan-" + start)).get().extracting(HandoffRecord::status).isEqualTo("scheduled");
    }

    @Test
    void handoffCoversPromisesAndHandsThemBack() {
        track("faq", "karan", "write the launch FAQ for support", 12);
        String start = inDays(5);
        String end = inDays(9);
        Absence absence = new Absence("demo", "karan", start, end, "leave", "test");
        assertThat(stack.scheduler.schedule(absence)).isTrue();
        assertThat(stack.scheduler.schedule(absence)).isFalse(); // the same absence twice starts one handoff

        // Three days before: the plan asks Nikhil (same role) and tells Karan.
        env.sleep(Duration.ofDays(3));
        HandoffWorkflow handoff = client.newWorkflowStub(HandoffWorkflow.class, "handoff-" + absence.handoffId());
        assertThat(handoff.state().status()).isEqualTo("awaiting_cover");
        InboxItem ask = openItem("nikhil", "Can you cover \"write the launch FAQ for support\"");
        assertThat(ask.actions()).containsExactly("accept", "decline");
        assertThat(openItem("karan", "1 promise needs cover").text()).contains("nikhil");

        click(ask, "accept", "nikhil", null);
        env.sleep(Duration.ofMinutes(1));
        assertThat(handoff.state().status()).isEqualTo("covered");

        // First day away: the promise moves to Nikhil.
        env.sleep(Duration.ofDays(3));
        assertThat(handoff.state().status()).isEqualTo("active");
        assertThat(promiseWf("faq").state().owner()).isEqualTo("nikhil");
        assertThat(openItem("nikhil", "You now own the promise to write the launch FAQ")).isNotNull();

        // The day after the absence: it moves back, with a summary.
        env.sleep(Duration.ofDays(6));
        assertThat(handoff.state().status()).isEqualTo("returned");
        assertThat(promiseWf("faq").state().owner()).isEqualTo("karan");
        assertThat(openItem("karan", "Welcome back").text()).contains("covered by nikhil");
        assertThat(stack.handoffs.getHandoff("demo", absence.handoffId())).get()
                .satisfies(h -> assertThat(h.items()).singleElement().extracting(HandoffRecord.ItemState::state).isEqualTo("returned"));
    }

    @Test
    void declinedCoverGoesToTheLeadWhoAssignsSomeoneElse() {
        track("faq", "karan", "write the launch FAQ for support", 12);
        Absence absence = new Absence("demo", "karan", inDays(5), inDays(9), "leave", "test");
        stack.scheduler.schedule(absence);
        env.sleep(Duration.ofDays(3));

        click(openItem("nikhil", "Can you cover"), "decline", "nikhil", null);
        env.sleep(Duration.ofMinutes(1));
        assertThat(openItem("aditi", "Nobody is lined up to cover")).isNotNull();

        // The lead chooses someone on the Handoffs page, which sends an assign response.
        client.newWorkflowStub(HandoffWorkflow.class, "handoff-" + absence.handoffId())
                .respond(new Contracts.HandoffResponse("assign", "faq", "aditi", "zoya", "web:faq:zoya"));
        env.sleep(Duration.ofMinutes(1));
        click(openItem("zoya", "Can you cover"), "accept", "zoya", null);
        env.sleep(Duration.ofDays(3));
        assertThat(promiseWf("faq").state().owner()).isEqualTo("zoya");
    }
}
