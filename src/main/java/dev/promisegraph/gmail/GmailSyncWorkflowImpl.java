package dev.promisegraph.gmail;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Message;
import dev.promisegraph.gmail.GmailSyncContracts.FetchRequest;
import dev.promisegraph.gmail.GmailSyncContracts.FetchResult;
import dev.promisegraph.gmail.GmailSyncContracts.SyncState;
import dev.promisegraph.ingestion.ExtractionInput;
import dev.promisegraph.temporal.ExtractionWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;

public class GmailSyncWorkflowImpl implements GmailSyncWorkflow {

    /** Fetches per run before continue-as-new keeps the history small. */
    static final int ROUNDS_BEFORE_CONTINUE = 100;
    static final int MAX_MESSAGES_PER_FETCH = 50;
    static final int BATCH_SIZE = 20;

    private static final Logger log = Workflow.getLogger(GmailSyncWorkflowImpl.class);

    private final GmailActivities gmail = Workflow.newActivityStub(GmailActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(3))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofMinutes(5))
                            .setDoNotRetry("GmailAccessDenied")
                            .build())
                    .build());

    private SyncState state;
    private boolean syncRequested;

    @Override
    public void run(SyncState initial) {
        state = initial;
        for (int round = 0; round < ROUNDS_BEFORE_CONTINUE; round++) {
            FetchResult result = gmail.fetchNewEmails(
                    new FetchRequest(state.tenant(), state.historyId(), state.backfillDays(), MAX_MESSAGES_PER_FETCH, state.mailbox()));
            extract(result.messages());
            state = state.withProgress(result.nextHistoryId(), result.messages().size());
            if (result.resynced()) {
                log.warn("Gmail cursor had expired; resynced recent mail");
            }
            if (!result.hasMore()) {
                Workflow.await(Duration.ofSeconds(state.pollSeconds()), () -> syncRequested);
            }
            syncRequested = false; // a request that arrived during this round is covered by the next fetch
        }
        Workflow.continueAsNew(state);
    }

    /** One child extraction per batch, in order. Child IDs come from message IDs, so a replay restarts nothing new. */
    private void extract(List<Message> messages) {
        for (int i = 0; i < messages.size(); i += BATCH_SIZE) {
            List<Message> batch = messages.subList(i, Math.min(messages.size(), i + BATCH_SIZE));
            String id = Ids.extractionWorkflowId(state.tenant(), batch.stream().map(Message::id).toList());
            ExtractionWorkflow child = Workflow.newChildWorkflowStub(ExtractionWorkflow.class,
                    ChildWorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(TASK_QUEUE_MAIN).build());
            child.run(new ExtractionInput(state.tenant(), batch));
        }
    }

    @Override
    public void syncNow() {
        syncRequested = true;
    }

    @Override
    public SyncState state() {
        return state;
    }
}
