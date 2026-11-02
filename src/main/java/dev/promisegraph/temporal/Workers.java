package dev.promisegraph.temporal;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_LLM;
import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerOptions;
import java.util.function.BiFunction;

/** Registers workflows and activities on their task queues. Shared by the app and the workflow tests. */
public final class Workers {

    private Workers() {}

    /**
     * @param newWorker creates a worker for a task queue, e.g. {@code factory::newWorker}
     *     or {@code testEnv::newWorker}
     */
    public static void register(
            BiFunction<String, WorkerOptions, Worker> newWorker,
            PromiseActivities activities,
            ModelActivities modelActivities,
            int modelConcurrency) {
        Worker main = newWorker.apply(TASK_QUEUE_MAIN, WorkerOptions.getDefaultInstance());
        main.registerWorkflowImplementationTypes(ExtractionWorkflowImpl.class, PromiseWorkflowImpl.class, DigestWorkflowImpl.class);
        main.registerActivitiesImplementations(activities);

        Worker llm = newWorker.apply(TASK_QUEUE_LLM,
                WorkerOptions.newBuilder().setMaxConcurrentActivityExecutionSize(modelConcurrency).build());
        llm.registerActivitiesImplementations(modelActivities);
    }
}
