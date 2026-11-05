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

    private HandoffWorkflow handoff(Absence a) {
        return client.newWorkflowStub(HandoffWorkflow.class, "handoff-" + a.handoffId());
    }

    private void approve(String lead) {
        click(openItem(lead, "Change who takes what on the Handoffs page, then approve"), "approve plan", lead, null);
        env.sleep(Duration.ofMinutes(1));
    }

    @Test
    void theLeadAssignsThenPromisesMoveAndComeBack() {
        track("faq", "karan", "write the launch FAQ for support", 12);
        String start = inDays(5);
        String end = inDays(9);
        Absence absence = new Absence("demo", "karan", start, end, "leave", "test");
        assertThat(stack.scheduler.schedule(absence)).isTrue();
        assertThat(stack.scheduler.schedule(absence)).isFalse(); // the same absence twice starts one handoff

        // As soon as the leave is known, Karan's lead (Aditi) sees every open promise, with a suggestion.
        env.sleep(Duration.ofMinutes(5));
        assertThat(handoff(absence).state().status()).isEqualTo("awaiting_approval");
        InboxItem plan = openItem("aditi", "karan is on leave from " + start + " to " + end);
        assertThat(plan.actions()).containsExactly("approve plan");
        assertThat(plan.text()).contains("write the launch FAQ for support (due " + inDays(12) + ") → suggested: nikhil");
        assertThat(openItem("karan", "to aditi to assign")).isNotNull();
        assertThat(stack.inbox.inbox("demo", "nikhil", false)).isEmpty(); // nobody gets anything before approval

        // Karan can't approve his own handover.
        handoff(absence).respond(new Contracts.HandoffResponse("approve plan", "plan", "karan", null, "web:approve-karan"));
        env.sleep(Duration.ofMinutes(1));
        assertThat(handoff(absence).state().status()).isEqualTo("awaiting_approval");

        approve("aditi");
        assertThat(handoff(absence).state().status()).isEqualTo("covered");
        assertThat(openItem("nikhil", "aditi assigned you \"write the launch FAQ for support\"").actions()).isEmpty();
        assertThat(openItem("karan", "aditi assigned your open promises").text()).contains("→ nikhil");

        // First day away: the promise moves to Nikhil, no answer needed.
        env.sleep(Duration.ofDays(6));
        assertThat(handoff(absence).state().status()).isEqualTo("active");
        assertThat(promiseWf("faq").state().owner()).isEqualTo("nikhil");

        // The day after the absence: it moves back, with a summary.
        env.sleep(Duration.ofDays(6));
        assertThat(handoff(absence).state().status()).isEqualTo("returned");
        assertThat(promiseWf("faq").state().owner()).isEqualTo("karan");
        assertThat(openItem("karan", "Welcome back").text()).contains("covered by nikhil");
    }

    @Test
    void theLeadCanChangeWhoTakesWhatBeforeApproving() {
        track("faq", "karan", "write the launch FAQ for support", 12);
        Absence absence = new Absence("demo", "karan", inDays(5), inDays(9), "leave", "test");
        stack.scheduler.schedule(absence);
        env.sleep(Duration.ofMinutes(5));

        handoff(absence).respond(new Contracts.HandoffResponse("assign", "faq", "karan", "omar", "web:faq:omar")); // not his call
        handoff(absence).respond(new Contracts.HandoffResponse("assign", "faq", "aditi", "zoya", "web:faq:zoya"));
        env.sleep(Duration.ofMinutes(1));
        approve("aditi");
        assertThat(openItem("zoya", "aditi assigned you")).isNotNull();
        assertThat(stack.inbox.inbox("demo", "nikhil", false)).isEmpty();
        env.sleep(Duration.ofDays(6));
        assertThat(promiseWf("faq").state().owner()).isEqualTo("zoya");
    }

    @Test
    void approvedAfterTheStartMovesAtOnce() {
        track("faq", "karan", "write the launch FAQ for support", 12);
        Absence absence = new Absence("demo", "karan", inDays(1), inDays(9), "leave", "test");
        stack.scheduler.schedule(absence);

        env.sleep(Duration.ofDays(2));
        assertThat(handoff(absence).state().status()).isEqualTo("awaiting_approval");
        assertThat(promiseWf("faq").state().owner()).isEqualTo("karan");
        assertThat(openItem("aditi", "karan's leave has started")).isNotNull();

        approve("aditi");
        assertThat(promiseWf("faq").state().owner()).isEqualTo("nikhil");
        assertThat(handoff(absence).state().status()).isEqualTo("active");
    }

    @Test
    void promisesMadeAfterApprovalGoToTheLeadToAssign() {
        track("faq", "karan", "write the launch FAQ for support", 30);
        Absence absence = new Absence("demo", "karan", inDays(10), inDays(14), "leave", "test");
        stack.scheduler.schedule(absence);
        env.sleep(Duration.ofMinutes(5));
        approve("aditi");

        env.sleep(Duration.ofDays(2));
        track("notes", "karan", "send the release notes", 20); // made after the plan

        env.sleep(Duration.ofDays(9)); // the leave has started
        assertThat(promiseWf("faq").state().owner()).isEqualTo("nikhil");
        assertThat(promiseWf("notes").state().owner()).isEqualTo("karan");
        assertThat(openItem("aditi", "\"send the release notes\" (karan's) isn't assigned")).isNotNull();

        handoff(absence).respond(new Contracts.HandoffResponse("assign", "notes", "aditi", "zoya", "web:notes:zoya"));
        env.sleep(Duration.ofMinutes(1));
        assertThat(promiseWf("notes").state().owner()).isEqualTo("zoya");
        assertThat(openItem("zoya", "It's yours from now")).isNotNull();
    }
}
