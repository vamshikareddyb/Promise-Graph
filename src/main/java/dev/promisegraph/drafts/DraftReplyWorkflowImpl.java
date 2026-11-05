package dev.promisegraph.drafts;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_LLM;

import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.DraftRequest;
import dev.promisegraph.drafts.DraftContracts.Prepared;
import dev.promisegraph.drafts.DraftContracts.Written;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;

public class DraftReplyWorkflowImpl implements DraftReplyWorkflow {

    private static final Logger log = Workflow.getLogger(DraftReplyWorkflowImpl.class);

    private final DraftActivities io = Workflow.newActivityStub(DraftActivities.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(2))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(1))
                    .setMaximumAttempts(8)
                    .build())
            .build());

    private final DraftWriterActivities writer = Workflow.newActivityStub(DraftWriterActivities.class, ActivityOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE_LLM)
            .setStartToCloseTimeout(Duration.ofMinutes(3)) // a local model on a laptop can be slow
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(3))
                    .setBackoffCoefficient(2.0)
                    .setMaximumInterval(Duration.ofMinutes(1))
                    .setMaximumAttempts(4)
                    .build())
            .build());

    @Override
    public String run(DraftRequest request) {
        Prepared prepared = io.prepareDraft(request);
        if (prepared.context() == null) {
            return "skipped: " + prepared.skipped();
        }
        DraftContext ctx = prepared.context();
        Written written;
        try {
            written = writer.writeReply(ctx);
        } catch (ActivityFailure e) {
            // The model stayed unavailable: still leave the person a starting point.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String why = cause instanceof io.temporal.failure.ApplicationFailure af ? af.getOriginalMessage() : cause.getMessage();
            log.warn("draft for {} written without the AI writer: {}", ctx.sourceMessageId(), why);
            written = new Written(TemplateReplyDrafter.compose(ctx), TemplateReplyDrafter.by("the AI writer failed: " + why));
        }
        io.saveDraft(ReplyDraft.of(ctx, written, Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString()), request.replace());
        return "drafted";
    }
}
