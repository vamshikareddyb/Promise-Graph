package dev.promisegraph.config;

import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.extraction.Extractor;
import dev.promisegraph.gmail.GmailActivitiesImpl;
import dev.promisegraph.notify.Notifier;
import dev.promisegraph.sim.Company;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.DigestWorkflow;
import dev.promisegraph.temporal.ModelActivitiesImpl;
import dev.promisegraph.temporal.PromiseActivitiesImpl;
import dev.promisegraph.temporal.Workers;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal client, workers and schedules. */
@Configuration
public class TemporalConfig {

    private static final Logger log = LoggerFactory.getLogger(TemporalConfig.class);

    /** 08:47 India time on weekdays (03:17 UTC). Off the hour so runs don't pile up at :00. */
    static final String DIGEST_CRON = "17 3 * * 1-5";

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs serviceStubs(PromiseGraphProperties props) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(props.temporal().address()).build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs stubs, PromiseGraphProperties props) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder().setNamespace(props.temporal().namespace()).build());
    }

    @Bean
    PromiseActivitiesImpl promiseActivities(Repository repo, Notifier notifier, TimingPolicy policy,
            PromiseGraphProperties props, WorkflowClient client) {
        return new PromiseActivitiesImpl(repo, notifier, policy, props.confidenceThreshold(), client);
    }

    @Bean
    ModelActivitiesImpl modelActivities(Extractor extractor) {
        // Phase 1 uses the simulated company as its directory. A directory sync replaces this later.
        return new ModelActivitiesImpl(extractor, Company.directory());
    }

    @Bean(destroyMethod = "shutdown")
    WorkerFactory workerFactory(WorkflowClient client, PromiseActivitiesImpl activities, ModelActivitiesImpl model,
            PromiseGraphProperties props, ObjectProvider<GmailActivitiesImpl> gmailActivities) {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker main = Workers.register(factory::newWorker, activities, model, props.llmMaxConcurrency());
        // Present only when promisegraph.gmail.enabled=true. Registered before the factory starts.
        gmailActivities.ifAvailable(main::registerActivitiesImplementations);
        return factory;
    }

    /** Starts the workers and makes sure the schedules exist, unless this instance only serves HTTP or replays. */
    @Bean
    ApplicationRunner startWorkers(WorkerFactory factory, WorkflowServiceStubs stubs, PromiseGraphProperties props) {
        return args -> {
            if (!props.temporal().workerEnabled()) {
                log.info("worker disabled on this instance");
                return;
            }
            factory.start();
            ensureSchedules(stubs, props);
            log.info("worker running: tenant={} extractor={} store={}", props.tenant(), props.extractor(), props.store());
        };
    }

    static void ensureSchedules(WorkflowServiceStubs stubs, PromiseGraphProperties props) {
        ScheduleClient schedules = ScheduleClient.newInstance(stubs,
                ScheduleClientOptions.newBuilder().setNamespace(props.temporal().namespace()).build());
        Schedule schedule = Schedule.newBuilder()
                .setAction(ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType(DigestWorkflow.class)
                        .setArguments(props.tenant())
                        .setOptions(WorkflowOptions.newBuilder()
                                .setWorkflowId("digest-run-" + props.tenant())
                                .setTaskQueue(PromiseGraphProperties.TASK_QUEUE_MAIN)
                                .build())
                        .build())
                .setSpec(ScheduleSpec.newBuilder().setCronExpressions(List.of(DIGEST_CRON)).build())
                .build();
        try {
            schedules.createSchedule("digest-" + props.tenant(), schedule, ScheduleOptions.newBuilder().build());
            log.info("created digest schedule");
        } catch (ScheduleAlreadyRunningException e) {
            // already there
        }
    }
}
