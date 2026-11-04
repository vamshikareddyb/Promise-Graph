package dev.promisegraph.load;

import static dev.promisegraph.config.PromiseGraphProperties.TASK_QUEUE_MAIN;

import dev.promisegraph.TestStack;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Promise;
import dev.promisegraph.domain.PromiseStatus;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.recovery.RulePlanner;
import dev.promisegraph.sim.Company;
import dev.promisegraph.temporal.Contracts.PromiseSignal;
import dev.promisegraph.temporal.Contracts.PromiseWorkflowInput;
import dev.promisegraph.temporal.NegotiationActivitiesImpl;
import dev.promisegraph.temporal.PromiseWorkflow;
import dev.promisegraph.temporal.Workers;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Load and failure test against a real Temporal server (start one with {@code docker compose up -d}):
 *
 * <pre>./gradlew loadTest -Pcount=50000 -PkillAt=0.5</pre>
 *
 * <p>Starts {@code count} promise workflows, has every owner mark theirs kept, and waits until all
 * are closed. With {@code killAt}, the worker is shut down once that share is done and a new one
 * takes over, as when a server dies. It checks that every promise was kept and every version was
 * recorded once, and prints the rates. Stores are in memory, so this measures Temporal and the
 * workflow code, not Postgres. Real time can't be skipped, so reminders and missed dates are not
 * exercised here; {@code LoadAndChaosTest} covers those on the test server.
 */
public final class LoadRunner {

    private LoadRunner() {}

    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(arg(args, "--count", "1000"));
        double killAt = Double.parseDouble(arg(args, "--kill-at", "0"));
        String address = arg(args, "--address", System.getenv().getOrDefault("TEMPORAL_ADDRESS", "localhost:7233"));
        String namespace = arg(args, "--namespace", "default");
        int workers = Integer.parseInt(arg(args, "--worker-threads", "200"));

        WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
        WorkflowClient client = WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
        TestStack stack = new TestStack(client, Clock.systemUTC(), new HandoffPlanning.RulePlanner(), new RulePlanner(),
                NegotiationActivitiesImpl.Policy.defaults(), r -> r);
        WorkerFactory factory = startWorkers(client, stack, workers);

        String run = Long.toString(System.currentTimeMillis(), 36);
        String now = Instant.now().toString();
        String due = LocalDate.now(ZoneOffset.UTC).plusDays(30).toString();
        List<String> ids = new ArrayList<>(count);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        long t0 = System.nanoTime();
        for (int i = 0; i < count; i++) {
            String id = "load-" + run + "-" + i;
            ids.add(id);
            String owner = Company.PEOPLE.get(i % Company.PEOPLE.size()).id();
            pool.submit(() -> {
                Promise p = new Promise(id, "demo", owner, "deliver item " + id, PromiseStatus.ACTIVE, 0.95, now, now, null, due, "",
                        List.of(), 0);
                stack.repo.createPromise(p);
                PromiseWorkflow wf = client.newWorkflowStub(PromiseWorkflow.class, WorkflowOptions.newBuilder()
                        .setWorkflowId(Ids.promiseWorkflowId(id)).setTaskQueue(TASK_QUEUE_MAIN)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE).build());
                WorkflowClient.start(wf::run, PromiseWorkflowInput.fresh(p, TimingPolicy.defaults(), 0.8));
                wf.event(PromiseSignal.of("kept", "load-kept-" + id, owner));
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
        double startSeconds = (System.nanoTime() - t0) / 1e9;
        System.out.printf("started and signalled %d promises in %.1f s (%.0f/s)%n", count, startSeconds, count / startSeconds);

        boolean killed = killAt <= 0;
        int kept = 0;
        while (kept < count) {
            Thread.sleep(2000);
            kept = stack.repo.promisesByStatus("demo", EnumSet.of(PromiseStatus.KEPT)).size();
            System.out.printf("  %d of %d kept after %.0f s%n", kept, count, (System.nanoTime() - t0) / 1e9);
            if (!killed && kept >= count * killAt) {
                System.out.println("  killing the worker and starting a new one");
                factory.shutdownNow();
                factory = startWorkers(client, stack, workers);
                killed = true;
            }
        }
        double total = (System.nanoTime() - t0) / 1e9;
        int badVersions = 0;
        for (String id : ids) {
            Promise p = stack.repo.getPromise("demo", id).orElseThrow();
            if (stack.repo.events("demo", id).size() != p.version()) {
                badVersions++;
            }
        }
        System.out.printf("all %d promises kept in %.1f s (%.0f promises/s); %d with missing or repeated events%n",
                count, total, count / total, badVersions);
        factory.shutdownNow();
        stubs.shutdownNow();
        System.exit(badVersions == 0 ? 0 : 1);
    }

    private static WorkerFactory startWorkers(WorkflowClient client, TestStack stack, int threads) {
        WorkerFactory factory = WorkerFactory.newInstance(client, WorkerFactoryOptions.newBuilder().setMaxWorkflowThreadCount(threads * 4).build());
        Workers.register(factory::newWorker, stack.activities, 4);
        factory.start();
        return factory;
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
