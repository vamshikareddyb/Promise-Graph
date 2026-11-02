package dev.promisegraph.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.extraction.Extractor;
import dev.promisegraph.extraction.RuleBasedExtractor;
import dev.promisegraph.extraction.model.AnthropicExtractor;
import dev.promisegraph.ingestion.IngestionGateway;
import dev.promisegraph.notify.LogNotifier;
import dev.promisegraph.notify.Notifier;
import dev.promisegraph.store.InMemoryRepository;
import dev.promisegraph.store.JdbcRepository;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.ExtractionWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Application wiring: storage, extraction, notifications and the ingestion gateway. */
@Configuration
public class AppConfig {

    @Bean
    Repository repository(PromiseGraphProperties props, Environment env) {
        if ("memory".equals(props.store())) {
            return new InMemoryRepository();
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(env.getRequiredProperty("spring.datasource.url"));
        ds.setUsername(env.getProperty("spring.datasource.username"));
        ds.setPassword(env.getProperty("spring.datasource.password"));
        ds.setMaximumPoolSize(10);
        return new JdbcRepository(new NamedParameterJdbcTemplate(ds), new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Bean
    Extractor extractor(PromiseGraphProperties props, Environment env) {
        if ("llm".equals(props.extractor())) {
            return new AnthropicExtractor(env.getProperty("ANTHROPIC_API_KEY"), props.model(), 2000);
        }
        return new RuleBasedExtractor();
    }

    @Bean
    Notifier notifier() {
        return new LogNotifier();
    }

    @Bean
    TimingPolicy timingPolicy() {
        return TimingPolicy.defaults();
    }

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
