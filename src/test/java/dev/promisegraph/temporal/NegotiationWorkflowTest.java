package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.inbox.InboxItem;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.links.Link;
import dev.promisegraph.recovery.Move;
import dev.promisegraph.recovery.NegotiationRecord;
import dev.promisegraph.recovery.RecoveryPlan;
import dev.promisegraph.recovery.RulePlanner;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import dev.promisegraph.temporal.NegotiationContracts.ApplyStep;
import dev.promisegraph.temporal.NegotiationContracts.TeamSnapshot;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Negotiations end to end on the time-skipping test server: a slip that reaches a customer promise
 * starts one, the three leads choose a plan in their inboxes, and the plan is applied, or undone if
 * a step fails.
 *
 * <p>Neha's export API (platform) is due on day 10 and slips to day 24. Dev's ingestion job
 * (analytics, day 15) waits on it, and Vikram's Acme walkthrough (customer success, day 17) waits on
 * the job.
 */
class NegotiationWorkflowTest {

    private TestWorkflowEnvironment env;
    private WorkflowClient client;
    private TestStack stack;
    private LocalDate day0;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private void start(NegotiationActivitiesImpl.Policy policy, boolean failAcme) {
        env = TestWorkflowEnvironment.newInstance();
        client = env.getWorkflowClient();
        stack = new TestStack(env, new HandoffPlanning.RulePlanner(), new RulePlanner(), policy);
        if (failAcme) {
            // Fails the Acme step as if the promise had just closed, after the earlier steps were applied.
            NegotiationActivitiesImpl failing = new NegotiationActivitiesImpl(stack.situations, stack.repo, stack.negotiations,
                    stack.promises, client, policy, stack.clock) {
                @Override
                public void applyMove(ApplyStep step) {
                    if (step.move().promiseId().equals("acme")) {
                        throw ApplicationFailure.newNonRetryableFailure("\"send Acme the usage dashboard walkthrough\" has closed",
                                NegotiationActivitiesImpl.PROMISE_CLOSED);
                    }
                    super.applyMove(step);
                }
            };
            stack.register(env, stack.withNegotiations(failing));
        } else {
            stack.register(env);
        }
        env.start();
        day0 = LocalDate.ofInstant(Instant.ofEpochMilli(env.currentTimeMillis()), ZoneOffset.UTC);
    }

    private void start() {
        start(NegotiationActivitiesImpl.Policy.defaults(), false);
    }

    private String day(int n) {
        return day0.plusDays(n).toString();
    }

    private void track(String id, String owner, String what, int dueDay) {
        String now = Instant.ofEpochMilli(env.currentTimeMillis()).toString();
        Promise p = new Promise(id, "demo", owner, what, PromiseStatus.DETECTED, 0.9, now, now, null, day(dueDay), "", List.of(), 0);
        stack.repo.createPromise(p);
        PromiseWorkflow wf = client.newWorkflowStub(PromiseWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(Ids.promiseWorkflowId(id)).setTaskQueue(TASK_QUEUE_MAIN).build());
        WorkflowClient.start(wf::run, PromiseWorkflowInput.fresh(p, TimingPolicy.defaults(), 0.8));
    }

    private void link(String from, String to) {
        stack.links.addLink(new Link("demo", from, to, Link.Status.CONFIRMED, "test", "test", "test", "2026-11-02T05:00:00Z"));
    }

    private PromiseWorkflow promiseWf(String id) {
        return client.newWorkflowStub(PromiseWorkflow.class, Ids.promiseWorkflowId(id));
    }

    /** The export chain, activated, then the API slips by 14 days. */
    private NegotiationRecord slipTheApi() {
        track("api", "neha", "ship the data export API", 10);
        track("job", "dev", "build the export ingestion job", 15);
        track("acme", "vikram", "send Acme the usage dashboard walkthrough", 17);
        link("api", "job");
        link("job", "acme");
        env.sleep(Duration.ofHours(25)); // all three auto-activate
        promiseWf("api").event(new PromiseSignal("slip", "msg-slip", "neha", day(24), null, "The export API will slip two weeks", "", null));
        env.sleep(Duration.ofMinutes(5));
        return only();
    }

    private NegotiationRecord only() {
        List<NegotiationRecord> all = stack.negotiations.list("demo");
        assertThat(all).as("negotiations").hasSize(1);
        return all.get(0);
    }

    private InboxItem openItem(String person, String containing) {
        return stack.inbox.inbox("demo", person, true).stream().filter(i -> i.text().contains(containing)).findFirst()
                .orElseThrow(() -> new AssertionError("no open inbox item for " + person + " containing: " + containing
                        + "; inbox: " + stack.inbox.inbox("demo", person, false).stream().map(InboxItem::text).toList()));
    }

    private void choose(String person, String action, String reason) {
        InboxItem ask = openItem(person, "Choose a recovery plan");
        stack.inboxService.act("demo", ask.key(), new InboxService.ActionRequest(action, person, null, reason));
    }

    @Test
    void leadsApproveAPlanAndEveryoneGetsTheirChange() {
        start();
        NegotiationRecord n = slipTheApi();
        assertThat(n.status()).isEqualTo("awaiting_approval");
        assertThat(n.approvers()).containsExactly("meera", "priya", "ravi");
        assertThat(n.plans()).extracting(RecoveryPlan::title)
                .containsExactly("Accept the new dates", "Add arjun to win back time", "Protect the customer dates");
        assertThat(openItem("ravi", "Choose a recovery plan").actions()).containsExactly("approve A", "approve B", "approve C", "reject all");
        assertThat(openItem("dev", "Your lead is choosing a recovery plan")).isNotNull();

        // The platform team's workflow holds Arjun for plan B while the leads decide.
        TeamWorkflow platform = client.newWorkflowStub(TeamWorkflow.class, NegotiationContracts.teamWorkflowId("demo", "platform"));
        assertThat(platform.snapshot().reservations()).extracting(NegotiationContracts.Reservation::person).containsExactly("arjun");

        choose("ravi", "approve B", null);
        choose("meera", "approve B", null);
        env.sleep(Duration.ofMinutes(1));
        assertThat(only().votes()).containsEntry("ravi", "B").containsEntry("meera", "B");
        assertThat(only().status()).isEqualTo("awaiting_approval");
        choose("priya", "approve B", null);
        env.sleep(Duration.ofMinutes(5));

        NegotiationRecord done = only();
        assertThat(done.status()).isEqualTo("applied");
        assertThat(done.chosen()).isEqualTo("B");
        assertThat(promiseWf("api").state().due()).isEqualTo(day(17));
        assertThat(promiseWf("api").state().status()).isEqualTo(PromiseStatus.ACTIVE);
        assertThat(promiseWf("job").state().due()).isEqualTo(day(22));
        assertThat(promiseWf("acme").state().due()).isEqualTo(day(24));
        assertThat(openItem("arjun", "asks you to help neha with ship the data export API")).isNotNull();
        assertThat(openItem("vikram", "moves your promise to send Acme the usage dashboard walkthrough")).isNotNull();
        assertThat(openItem("neha", "Recovery plan for \"ship the data export API\": Plan B")).isNotNull();

        // Applying the plan's own changes does not start another negotiation.
        assertThat(stack.negotiations.list("demo")).hasSize(1);
        // Arjun stays held for the chosen plan.
        TeamSnapshot after = platform.snapshot();
        assertThat(after.reservations()).extracting(NegotiationContracts.Reservation::person).containsExactly("arjun");
    }

    @Test
    void rejectedPlansAreNotOfferedAgain() {
        start();
        NegotiationRecord n = slipTheApi();
        List<String> first = n.plans().stream().map(RecoveryPlan::signature).toList();
        choose("ravi", "reject all", "Arjun is on the billing migration");
        choose("meera", "reject all", null);
        choose("priya", "reject all", null);
        env.sleep(Duration.ofMinutes(5));

        NegotiationRecord second = only();
        assertThat(second.round()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("awaiting_approval");
        assertThat(second.plans()).isNotEmpty().noneMatch(p -> first.contains(p.signature()));
        assertThat(second.plans()).allSatisfy(p -> assertThat(p.moves()).noneMatch(m -> "arjun".equals(m.person())));
        assertThat(second.history()).anyMatch(h -> h.contains("ravi rejected the plans: Arjun is on the billing migration"));
        // The round-1 hold on Arjun was released.
        TeamWorkflow platform = client.newWorkflowStub(TeamWorkflow.class, NegotiationContracts.teamWorkflowId("demo", "platform"));
        assertThat(platform.snapshot().reservations()).noneMatch(r -> r.person().equals("arjun"));
    }

    @Test
    void withoutAnswersItRemindsThenExpiresChangingNothing() {
        start();
        slipTheApi();
        env.sleep(Duration.ofHours(25));
        assertThat(openItem("priya", "Reminder: a recovery plan is waiting")).isNotNull();
        env.sleep(Duration.ofHours(48));

        NegotiationRecord n = only();
        assertThat(n.status()).isEqualTo("expired");
        assertThat(promiseWf("job").state().due()).isEqualTo(day(15));
        assertThat(promiseWf("acme").state().due()).isEqualTo(day(17));
        assertThat(openItem("neha", "No plan was approved")).isNotNull();
    }

    @Test
    void aFailedStepUndoesTheStepsBeforeIt() {
        start(NegotiationActivitiesImpl.Policy.defaults(), true);
        slipTheApi();
        for (String lead : List.of("meera", "priya", "ravi")) {
            choose(lead, "approve A", null);
        }
        env.sleep(Duration.ofMinutes(5));

        NegotiationRecord n = only();
        assertThat(n.status()).isEqualTo("failed");
        assertThat(n.history()).anyMatch(h -> h.contains("could not be applied") && h.contains("1 change already made was undone"));
        // Plan A moved the job first; the failure on the Acme step put it back.
        assertThat(promiseWf("job").state().due()).isEqualTo(day(15));
        assertThat(stack.repo.events("demo", "job")).extracting(e -> e.kind()).contains("edited");
        assertThat(openItem("dev", "The recovery plan was called off")).isNotNull();
    }

    @Test
    void aSlipInsideOneTeamOnlyWarns() {
        start();
        track("api", "neha", "ship the data export API", 10);
        track("dash", "kabir", "build the export status page", 14);
        link("api", "dash");
        env.sleep(Duration.ofHours(25));
        promiseWf("api").event(new PromiseSignal("slip", "msg-slip", "neha", day(20), null, "slipping", "", null));
        env.sleep(Duration.ofMinutes(5));

        assertThat(promiseWf("dash").state().status()).isEqualTo(PromiseStatus.AT_RISK);
        assertThat(stack.negotiations.list("demo")).isEmpty();
    }

    @Test
    void aNewerSlipReplacesTheOpenNegotiation() {
        start();
        NegotiationRecord first = slipTheApi();
        promiseWf("api").event(new PromiseSignal("slip", "msg-slip-2", "neha", day(30), null, "Another week", "", null));
        env.sleep(Duration.ofMinutes(5));

        List<NegotiationRecord> all = stack.negotiations.list("demo");
        assertThat(all).hasSize(2);
        assertThat(stack.negotiations.get("demo", first.id())).get().extracting(NegotiationRecord::status).isEqualTo("superseded");
        NegotiationRecord newer = all.stream().filter(n -> !n.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(newer.status()).isEqualTo("awaiting_approval");
        assertThat(newer.newDue()).isEqualTo(day(30));
        // The job was already due before the API's previous date, so it now lands with the API, not before it.
        assertThat(newer.plans().get(0).moves()).extracting(Move::promiseId, Move::toDue).contains(
                org.assertj.core.groups.Tuple.tuple("job", day(30)), org.assertj.core.groups.Tuple.tuple("acme", day(32)));
    }
}
