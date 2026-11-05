package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_LLM;
import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import dev.promisegraph.drafts.DraftActivities;
import dev.promisegraph.drafts.DraftReplyWorkflowImpl;
import dev.promisegraph.drafts.DraftWriterActivities;
import dev.promisegraph.gmail.GmailSyncWorkflowImpl;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerOptions;
import java.util.function.BiFunction;

/** Registers workflows and activities on their task queues. Shared by the app and the workflow tests. */
public final class Workers {

    private Workers() {}

    /** Every activity implementation, grouped by the task queue it runs on. */
    public record Activities(
            PromiseActivities promises,
            LinkActivities links,
            HandoffActivities handoffs,
            ModelActivities model,
            PlanningActivities planning,
            NegotiationActivities negotiations,
            RecoveryActivities recovery,
            OpsActivities ops,
            DraftActivities drafts,
            DraftWriterActivities draftWriter) {}

    /**
     * @param newWorker creates a worker for a task queue, e.g. {@code factory::newWorker}
     *     or {@code testEnv::newWorker}
     * @return the main-queue worker, for optional sources such as Gmail to add their activities
     */
    public static Worker register(BiFunction<String, WorkerOptions, Worker> newWorker, Activities activities, int modelConcurrency) {
        Worker main = newWorker.apply(TASK_QUEUE_MAIN, WorkerOptions.getDefaultInstance());
        main.registerWorkflowImplementationTypes(ExtractionWorkflowImpl.class, PromiseWorkflowImpl.class,
                DigestWorkflowImpl.class, GmailSyncWorkflowImpl.class, RippleWorkflowImpl.class, HandoffWorkflowImpl.class,
                NegotiationWorkflowImpl.class, TeamWorkflowImpl.class, ReconcileWorkflowImpl.class, DraftReplyWorkflowImpl.class);
        main.registerActivitiesImplementations(activities.promises(), activities.links(), activities.handoffs(), activities.negotiations(),
                activities.ops(), activities.drafts());

        // Model calls (extraction, the handoff agent, the option agent, reply drafts) get their own queue and a concurrency cap.
        Worker llm = newWorker.apply(TASK_QUEUE_LLM,
                WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(modelConcurrency).build());
        llm.registerActivitiesImplementations(activities.model(), activities.planning(), activities.recovery(), activities.draftWriter());
        return main;
    }
}
