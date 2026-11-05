package dev.promisegraph.gmail;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Message;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.gmail.GmailSyncContracts.FetchRequest;
import dev.promisegraph.gmail.GmailSyncContracts.FetchResult;
import dev.promisegraph.gmail.GmailSyncContracts.SyncState;
import dev.promisegraph.store.InMemoryRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The mailbox sync loop on the time-skipping test server, feeding the real extraction pipeline. */
class GmailSyncWorkflowTest {

    /** First call returns one new email; later calls return nothing new. Records every cursor it was asked for. */
    static final class ScriptedGmail implements GmailActivities {
        final List<String> cursorsRequested = new ArrayList<>();
        final String ts;
        /** How many fetches Google refuses first, as when the Gmail permission wasn't granted. */
        int denials;

        ScriptedGmail(String ts) {
            this.ts = ts;
        }

        @Override
        public synchronized FetchResult fetchNewEmails(FetchRequest request) {
            cursorsRequested.add(String.valueOf(request.historyId()));
            if (denials > 0) {
                denials--;
                throw ApplicationFailure.newNonRetryableFailure(
                        "Gmail rejected the request (403: Request had insufficient authentication scopes.)", "GmailAccessDenied");
            }
            if (request.historyId() == null) {
                Message email = new Message("gmail-1", "email:t1", "alice@example.com", ts,
                        "Hi,\nI'll send the signed contract by Friday.\nAlice", request.tenant());
                return new FetchResult(List.of(email), "100", false, 2, false);
            }
            return new FetchResult(List.of(), request.historyId(), false, 0, false);
        }
    }

    private TestWorkflowEnvironment env;
    private InMemoryRepository repo;
    private ScriptedGmail gmail;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        TestStack stack = new TestStack(env);
        repo = stack.repo;
        Worker main = stack.register(env);
        gmail = new ScriptedGmail(Instant.ofEpochMilli(env.currentTimeMillis()).atOffset(ZoneOffset.UTC).toString());
        main.registerActivitiesImplementations(gmail);
        env.start();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void emailsBecomePromisesAndTheCursorAdvances() {
        GmailSyncWorkflow wf = env.getWorkflowClient().newWorkflowStub(GmailSyncWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("gmail-sync-demo").setTaskQueue(TASK_QUEUE_MAIN).build());
        WorkflowClient.start(wf::run, new SyncState("demo", null, 60, 2, 0, null));

        env.sleep(Duration.ofMinutes(3)); // three polling rounds

        SyncState state = wf.state();
        assertThat(state.historyId()).isEqualTo("100");
        assertThat(state.processed()).isEqualTo(1);
        assertThat(gmail.cursorsRequested).startsWith("null", "100", "100");

        List<Promise> open = repo.openPromises("demo", List.of("alice@example.com"));
        assertThat(open).singleElement().satisfies(p -> {
            assertThat(p.what()).isEqualTo("send the signed contract");
            assertThat(p.evidence()).singleElement().satisfies(e -> assertThat(e.messageId()).isEqualTo("gmail-1"));
        });
    }

    @Test
    void syncNowSkipsTheWait() {
        GmailSyncWorkflow wf = env.getWorkflowClient().newWorkflowStub(GmailSyncWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("gmail-sync-now").setTaskQueue(TASK_QUEUE_MAIN).build());
        WorkflowClient.start(wf::run, new SyncState("demo", null, 3600, 2, 0, null));
        env.sleep(Duration.ofSeconds(5));
        int before = gmail.cursorsRequested.size();

        wf.syncNow();
        env.sleep(Duration.ofSeconds(5)); // far less than the one-hour poll interval
        assertThat(gmail.cursorsRequested.size()).isGreaterThan(before);
    }

    @Test
    void refusedAccessWaitsForTheNextSignInInsteadOfFailing() {
        gmail.denials = 1;
        GmailSyncWorkflow wf = env.getWorkflowClient().newWorkflowStub(GmailSyncWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId("gmail-sync-demo-denied").setTaskQueue(TASK_QUEUE_MAIN).build());
        WorkflowClient.start(wf::run, SyncState.forAccount("demo", "alice@example.com", 60, 2));
        env.sleep(Duration.ofMinutes(5));

        assertThat(wf.problem()).contains("insufficient authentication scopes");
        assertThat(gmail.cursorsRequested).hasSize(1); // waiting, not hammering Google
        WorkflowExecutionStatus status = env.getWorkflowClient().getWorkflowServiceStubs().blockingStub()
                .describeWorkflowExecution(io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(env.getNamespace())
                        .setExecution(WorkflowStub.fromTyped(wf).getExecution()).build())
                .getWorkflowExecutionInfo().getStatus();
        assertThat(status).isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

        wf.syncNow(); // what signing in again sends
        env.sleep(Duration.ofSeconds(30));
        assertThat(wf.problem()).isNull();
        assertThat(wf.state().processed()).isEqualTo(1);
    }
}
