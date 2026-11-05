package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.handoff.Absence;
import dev.promisegraph.handoff.AbsenceDetector;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.inbox.InboxService;
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

/** Someone leaving the company: their lead approves the plan, and their promises move for good. */
class DepartureWorkflowTest {

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

    private String inDays(int days) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(env.currentTimeMillis()).plus(Duration.ofDays(days)), ZoneOffset.UTC).toString();
    }

    private void track(String id, String owner, String what, int dueInDays) {
        String now = Instant.ofEpochMilli(env.currentTimeMillis()).toString();
        Promise p = new Promise(id, "demo", owner, what, PromiseStatus.DETECTED, 0.9, now, now, null, inDays(dueInDays), "", List.of(), 0);
        stack.repo.createPromise(p);
        WorkflowClient.start(client.newWorkflowStub(PromiseWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(Ids.promiseWorkflowId(id)).setTaskQueue(TASK_QUEUE_MAIN).build())::run,
                PromiseWorkflowInput.fresh(p, TimingPolicy.defaults(), 0.8));
    }

    private PromiseWorkflow promiseWf(String id) {
        return client.newWorkflowStub(PromiseWorkflow.class, Ids.promiseWorkflowId(id));
    }

    private HandoffWorkflow handoff(Absence a) {
        return client.newWorkflowStub(HandoffWorkflow.class, "handoff-" + a.handoffId());
    }

    private InboxItem openItem(String person, String containing) {
        return stack.inbox.inbox("demo", person, true).stream().filter(i -> i.text().contains(containing)).findFirst()
                .orElseThrow(() -> new AssertionError("no open inbox item for " + person + " containing: " + containing
                        + "; inbox: " + stack.inbox.inbox("demo", person, false).stream().map(InboxItem::text).toList()));
    }

    private boolean hasItem(String person, String containing) {
        return stack.inbox.inbox("demo", person, false).stream().anyMatch(i -> i.text().contains(containing));
    }

    private void click(InboxItem item, String action, String by) {
        stack.inboxService.act("demo", item.key(), new InboxService.ActionRequest(action, by, null, null));
    }

    @Test
    void theLeadApprovesThenPromisesMoveForGood() {
        track("faq", "karan", "write the launch FAQ for support", 30);
        Absence leaving = new Absence("demo", "karan", inDays(5), null, AbsenceDetector.DEPARTURE_REASON, "test");
        String lastDay = inDays(4);
        stack.scheduler.schedule(leaving);

        // The plan goes to Karan's lead (Aditi) first. Nobody is asked yet.
        env.sleep(Duration.ofDays(3));
        assertThat(handoff(leaving).state().status()).isEqualTo("awaiting_approval");
        InboxItem approval = openItem("aditi", "karan is leaving: their last day is " + lastDay);
        assertThat(approval.actions()).containsExactly("approve plan");
        assertThat(approval.text()).contains("write the launch FAQ for support (due " + inDays(30) + ") → suggested: nikhil");
        assertThat(hasItem("nikhil", "assigned you")).isFalse();

        click(approval, "approve plan", "aditi");
        env.sleep(Duration.ofMinutes(1));
        assertThat(handoff(leaving).state().status()).isEqualTo("covered");
        assertThat(openItem("nikhil", "aditi assigned you \"write the launch FAQ for support\"").text()).contains("for good");
        assertThat(openItem("karan", "aditi assigned your open promises")).isNotNull();

        // The day after the last day it moves, and it never comes back.
        env.sleep(Duration.ofDays(3));
        assertThat(handoff(leaving).state().status()).isEqualTo("handed_over");
        assertThat(promiseWf("faq").state().owner()).isEqualTo("nikhil");
        env.sleep(Duration.ofDays(20));
        assertThat(promiseWf("faq").state().owner()).isEqualTo("nikhil");
    }

    @Test
    void withoutApprovalNothingMovesAndTheLeadIsToldOnce() {
        track("faq", "karan", "write the launch FAQ for support", 30);
        Absence leaving = new Absence("demo", "karan", inDays(5), null, AbsenceDetector.DEPARTURE_REASON, "test");
        stack.scheduler.schedule(leaving);

        env.sleep(Duration.ofDays(6)); // past the last day, still unapproved
        assertThat(promiseWf("faq").state().owner()).isEqualTo("karan");
        assertThat(handoff(leaving).state().status()).isEqualTo("awaiting_approval");
        InboxItem late = openItem("aditi", "has left and their 1 open promise(s) aren't assigned yet");

        // The person leaving can't approve their own handover.
        handoff(leaving).respond(new Contracts.HandoffResponse("approve plan", "plan", "karan", null, "web:approve-karan"));
        env.sleep(Duration.ofMinutes(1));
        assertThat(handoff(leaving).state().status()).isEqualTo("awaiting_approval");

        click(late, "approve plan", "aditi");
        env.sleep(Duration.ofMinutes(1));
        assertThat(promiseWf("faq").state().owner()).isEqualTo("nikhil"); // approved after the last day: it moves at once
        assertThat(handoff(leaving).state().status()).isEqualTo("handed_over");
    }
}
