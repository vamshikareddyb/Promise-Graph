package dev.promisegraph;

import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.extraction.RuleBasedExtractor;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.handoff.HandoffScheduler;
import dev.promisegraph.inbox.InboxNotifier;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.inbox.TemporalSignals;
import dev.promisegraph.notify.Notifier;
import dev.promisegraph.sim.Company;
import dev.promisegraph.store.InMemoryRepository;
import dev.promisegraph.store.InMemoryStores;
import dev.promisegraph.temporal.HandoffActivitiesImpl;
import dev.promisegraph.temporal.LinkActivitiesImpl;
import dev.promisegraph.temporal.ModelActivitiesImpl;
import dev.promisegraph.temporal.PlanningActivitiesImpl;
import dev.promisegraph.temporal.PromiseActivitiesImpl;
import dev.promisegraph.temporal.Workers;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Everything the workflows need, in memory, wired the way the application wires it. */
public final class TestStack {

    public final InMemoryRepository repo = new InMemoryRepository();
    public final InMemoryStores.Links links = new InMemoryStores.Links();
    public final InMemoryStores.Inbox inbox = new InMemoryStores.Inbox();
    public final InMemoryStores.Handoffs handoffs = new InMemoryStores.Handoffs();
    public final RecordingNotifier recorded = new RecordingNotifier();
    public final Directory directory = new Directory(Company.PEOPLE);
    public final Clock clock;
    public final HandoffScheduler scheduler;
    public final InboxService inboxService;
    public final PromiseActivitiesImpl promises;
    public final Workers.Activities activities;

    /** Follows the test environment's skipped time, so dates computed by the app match the workflows. */
    static final class EnvClock extends Clock {
        private final TestWorkflowEnvironment env;

        EnvClock(TestWorkflowEnvironment env) {
            this.env = env;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(env.currentTimeMillis());
        }
    }

    public TestStack(TestWorkflowEnvironment env, HandoffPlanning.Planner planner) {
        WorkflowClient client = env.getWorkflowClient();
        clock = new EnvClock(env);
        InboxNotifier toInbox = new InboxNotifier(inbox, clock);
        Notifier notifier = n -> {
            toInbox.send(n);
            recorded.send(n);
        };
        scheduler = new HandoffScheduler(handoffs, client, 3, clock);
        inboxService = new InboxService(inbox, links, new TemporalSignals(client));
        promises = new PromiseActivitiesImpl(repo, links, notifier, TimingPolicy.defaults(), 0.8, client);
        activities = new Workers.Activities(
                promises,
                new LinkActivitiesImpl(repo, links, promises, client),
                new HandoffActivitiesImpl(repo, handoffs, scheduler, promises, client),
                new ModelActivitiesImpl(new RuleBasedExtractor(), Company.directory()),
                new PlanningActivitiesImpl(planner, repo, links, handoffs, directory));
    }

    public TestStack(TestWorkflowEnvironment env) {
        this(env, new HandoffPlanning.RulePlanner());
    }

    /** Registers every workflow and activity; returns the main worker. Call before {@code env.start()}. */
    public Worker register(TestWorkflowEnvironment env) {
        return Workers.register(env::newWorker, activities, 2);
    }
}
