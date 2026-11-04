package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_LLM;
import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

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
            PlanningActivities planning) {}

    /**
     * @param newWorker creates a worker for a task queue, e.g. {@code factory::newWorker}
     *     or {@code testEnv::newWorker}
     * @return the main-queue worker, for optional sources such as Gmail to add their activities
     */
    public static Worker register(BiFunction<String, WorkerOptions, Worker> newWorker, Activities activities, int modelConcurrency) {
        Worker main = newWorker.apply(TASK_QUEUE_MAIN, WorkerOptions.getDefaultInstance());
        main.registerWorkflowImplementationTypes(ExtractionWorkflowImpl.class, PromiseWorkflowImpl.class,
                DigestWorkflowImpl.class, GmailSyncWorkflowImpl.class, RippleWorkflowImpl.class, HandoffWorkflowImpl.class);
        main.registerActivitiesImplementations(activities.promises(), activities.links(), activities.handoffs());

        // Model calls (extraction, the handoff agent) get their own queue and a concurrency cap.
        Worker llm = newWorker.apply(TASK_QUEUE_LLM,
                WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(modelConcurrency).build());
        llm.registerActivitiesImplementations(activities.model(), activities.planning());
        return main;
    }
}
