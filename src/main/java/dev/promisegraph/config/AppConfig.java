package dev.promisegraph.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.extraction.Extractor;
import dev.promisegraph.extraction.RuleBasedExtractor;
import dev.promisegraph.extraction.model.AnthropicExtractor;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.handoff.HandoffPlanning;
import dev.promisegraph.handoff.HandoffScheduler;
import dev.promisegraph.handoff.agent.AnthropicModelClient;
import dev.promisegraph.handoff.agent.HandoffAgent;
import dev.promisegraph.inbox.InboxNotifier;
import dev.promisegraph.inbox.InboxService;
import dev.promisegraph.inbox.Signals;
import dev.promisegraph.inbox.TemporalSignals;
import dev.promisegraph.ingestion.IngestionGateway;
import dev.promisegraph.notify.Notifier;
import dev.promisegraph.sim.Company;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.store.InMemoryRepository;
import dev.promisegraph.store.InMemoryStores;
import dev.promisegraph.store.InboxStore;
import dev.promisegraph.store.JdbcRepository;
import dev.promisegraph.store.JdbcStores;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.ExtractionWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Application wiring: storage, extraction, notifications, handoffs and the ingestion gateway. */
@Configuration
public class AppConfig {

    // ------------------------------------------------------------ storage: memory (default) or Postgres

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "promisegraph", name = "store", havingValue = "jdbc")
    HikariDataSource dataSource(Environment env) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        ds.setUsername(env.getProperty("spring.datasource.username"));
        ds.setPassword(env.getProperty("spring.datasource.password"));
        ds.setMaximumPoolSize(10);
        return ds;
    }

    @Bean
    Repository repository(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryRepository()
                : new JdbcRepository(new NamedParameterJdbcTemplate(d), new TransactionTemplate(new DataSourceTransactionManager(d)));
    }

    @Bean
    LinkStore linkStore(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryStores.Links() : new JdbcStores.Links(new NamedParameterJdbcTemplate(d));
    }

    @Bean
    InboxStore inboxStore(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryStores.Inbox() : new JdbcStores.Inbox(new NamedParameterJdbcTemplate(d));
    }

    @Bean
    HandoffStore handoffStore(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryStores.Handoffs() : new JdbcStores.Handoffs(new NamedParameterJdbcTemplate(d));
    }

    // ------------------------------------------------------------ extraction, notifications, people

    @Bean
    Extractor extractor(PromiseGraphProperties props, Environment env) {
        if ("llm".equals(props.extractor())) {
            return new AnthropicExtractor(env.getProperty("ANTHROPIC_API_KEY"), props.model(), 2000);
        }
        return new RuleBasedExtractor();
    }

    @Bean
    Notifier notifier(InboxStore inbox) {
        return new InboxNotifier(inbox, Clock.systemUTC());
    }

    @Bean
    TimingPolicy timingPolicy() {
        return TimingPolicy.defaults();
    }

    /** Phase 2 uses the simulated company as its directory. A directory sync replaces this later. */
    @Bean
    Directory directory() {
        return new Directory(Company.PEOPLE);
    }

    // ------------------------------------------------------------ handoffs and the inbox

    @Bean
    HandoffPlanning.Planner handoffPlanner(HandoffProperties handoff, PromiseGraphProperties props, Environment env) {
        HandoffPlanning.Planner rules = new HandoffPlanning.RulePlanner();
        if ("agent".equals(handoff.planner())) {
            return new HandoffAgent(new AnthropicModelClient(env.getProperty("ANTHROPIC_API_KEY")), props.model(),
                    handoff.agentMaxSteps(), rules);
        }
        return rules;
    }

    @Bean
    HandoffScheduler handoffScheduler(HandoffStore store, WorkflowClient client, HandoffProperties handoff) {
        return new HandoffScheduler(store, client, handoff.prepDays(), Clock.systemUTC());
    }

    @Bean
    Signals signals(WorkflowClient client) {
        return new TemporalSignals(client);
    }

    @Bean
    InboxService inboxService(InboxStore inbox, LinkStore links, Signals signals) {
        return new InboxService(inbox, links, signals);
    }

    // ------------------------------------------------------------ ingestion

    @Bean
    IngestionGateway ingestionGateway(PromiseGraphProperties props, WorkflowClient client) {
        IngestionGateway.BatchStarter starter = (workflowId, input) -> {
            WorkflowOptions options = WorkflowOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setTaskQueue(PromiseGraphProperties.TASK_QUEUE_MAIN)
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                    .build();
            try {
                WorkflowClient.start(client.newWorkflowStub(ExtractionWorkflow.class, options)::run, input);
            } catch (WorkflowExecutionAlreadyStarted e) {
                // the same batch was already started: nothing to do
            }
        };
        return new IngestionGateway(props.tenant(), starter, props.gateway().maxBatch(), props.gateway().maxWait(), Clock.systemUTC());
    }

    /** Flushes partial batches on a timer. */
    @Component
    static class GatewayFlusher {
        private final IngestionGateway gateway;

        GatewayFlusher(IngestionGateway gateway) {
            this.gateway = gateway;
        }

        @Scheduled(fixedDelayString = "PT5S")
        void flush() {
            gateway.flushDue();
        }
    }
}
