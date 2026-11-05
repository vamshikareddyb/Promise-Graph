package dev.promisegraph.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.promisegraph.domain.TimingPolicy;
import dev.promisegraph.drafts.ClaudeReplyDrafter;
import dev.promisegraph.drafts.DraftStore;
import dev.promisegraph.drafts.InMemoryDraftStore;
import dev.promisegraph.drafts.JdbcDraftStore;
import dev.promisegraph.drafts.OllamaReplyDrafter;
import dev.promisegraph.drafts.ReplyDrafter;
import dev.promisegraph.drafts.TemplateReplyDrafter;
import dev.promisegraph.extraction.Extractor;
import dev.promisegraph.extraction.RuleBasedExtractor;
import dev.promisegraph.extraction.model.AnthropicExtractor;
import dev.promisegraph.gmail.MailboxOwner;
import dev.promisegraph.handoff.Directory;
import dev.promisegraph.team.Member;
import dev.promisegraph.team.TeamStore;
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
import dev.promisegraph.privacy.Visibility;
import dev.promisegraph.recovery.OptionAgent;
import dev.promisegraph.recovery.RecoveryPlanning;
import dev.promisegraph.recovery.RecoverySettings;
import dev.promisegraph.recovery.RulePlanner;
import dev.promisegraph.sim.Company;
import dev.promisegraph.store.HandoffStore;
import dev.promisegraph.store.InMemoryRepository;
import dev.promisegraph.store.InMemoryStores;
import dev.promisegraph.store.InboxStore;
import dev.promisegraph.store.JdbcRepository;
import dev.promisegraph.store.JdbcStores;
import dev.promisegraph.store.LinkStore;
import dev.promisegraph.store.NegotiationStore;
import dev.promisegraph.store.AuditLog;
import dev.promisegraph.store.DeadLetterStore;
import dev.promisegraph.store.Repository;
import dev.promisegraph.temporal.ExtractionWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

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

    @Bean
    NegotiationStore negotiationStore(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryStores.Negotiations() : new JdbcStores.Negotiations(new NamedParameterJdbcTemplate(d));
    }

    @Bean
    DeadLetterStore deadLetterStore(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryStores.DeadLetters() : new JdbcStores.DeadLetters(new NamedParameterJdbcTemplate(d));
    }

    @Bean
    AuditLog auditLog(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryStores.Audit() : new JdbcStores.Audit(new NamedParameterJdbcTemplate(d));
    }

    // ------------------------------------------------------------ extraction, notifications, people

    /**
     * Claude is the model behind every agent. Without ANTHROPIC_API_KEY the app still starts: each
     * agent falls back to its rule-based version and says so in the log.
     */
    static Optional<String> claudeKey(Environment env) {
        Optional<String> fromEnv = Optional.ofNullable(env.getProperty("ANTHROPIC_API_KEY")).map(AppConfig::cleanKey).filter(k -> !k.isEmpty());
        if (fromEnv.isPresent()) {
            logKeyOnce("the ANTHROPIC_API_KEY environment variable", fromEnv.get());
            return fromEnv;
        }
        // Saved once, so it works however the app is started (any terminal, the IDE, a script).
        java.nio.file.Path file = java.nio.file.Path.of(System.getProperty("user.home"), ".promise-graph", "anthropic-key.txt");
        try {
            if (java.nio.file.Files.isRegularFile(file)) {
                Optional<String> key = Optional.of(cleanKey(java.nio.file.Files.readString(file))).filter(k -> !k.isEmpty());
                key.ifPresent(k -> logKeyOnce(file.toString(), k));
                return key;
            }
        } catch (java.io.IOException e) {
            log.warn("could not read {}: {}", file, e.getMessage());
        }
        return Optional.empty();
    }

    /** Drops what copying a key tends to add: spaces, line breaks, a byte-order mark, surrounding quotes. */
    static String cleanKey(String raw) {
        String k = raw.replace("\uFEFF", "").strip();
        if (k.length() >= 2 && (k.startsWith("\"") && k.endsWith("\"") || k.startsWith("'") && k.endsWith("'"))) {
            k = k.substring(1, k.length() - 1).strip();
        }
        return k;
    }

    private static volatile boolean keyLogged;

    /** Says once where the key came from and which key it is (never the whole key), to check against the console. */
    private static void logKeyOnce(String source, String key) {
        if (keyLogged) {
            return;
        }
        keyLogged = true;
        String shown = key.length() <= 14 ? "(" + key.length() + " characters)" : key.substring(0, 10) + "..." + key.substring(key.length() - 4);
        log.info("Claude API key from {}: {}{}", source, shown,
                key.startsWith("sk-ant-api") ? "" : " - this doesn't look like an API key; they start with sk-ant-api");
    }

    private static void noKey(String what) {
        log.warn("ANTHROPIC_API_KEY is not set (nor ~/.promise-graph/anthropic-key.txt): {} uses the built-in rules instead of Claude", what);
    }

    @Bean
    Extractor extractor(PromiseGraphProperties props, Environment env) {
        if ("llm".equals(props.extractor())) {
            Optional<String> key = claudeKey(env);
            if (key.isPresent()) {
                log.info("promise extraction: Claude ({})", props.model());
                return new AnthropicExtractor(key.get(), props.model(), 2000);
            }
            noKey("promise extraction");
        }
        return new RuleBasedExtractor();
    }

    /** The reply-draft agent: Claude writes the reply; without a key, a plain acknowledgement is drafted. */
    @Bean
    ReplyDrafter replyDrafter(DraftProperties drafts, PromiseGraphProperties props, Environment env) {
        if ("ollama".equals(drafts.writer())) {
            log.info("reply drafts: local model {} through Ollama at {}", drafts.ollamaModel(), drafts.ollamaUrl());
            return new OllamaReplyDrafter(drafts.ollamaUrl(), drafts.ollamaModel(), drafts.maxTokens());
        }
        Optional<String> key = claudeKey(env);
        if ("claude".equals(drafts.writer()) && key.isPresent()) {
            String model = drafts.model() == null || drafts.model().isBlank() ? props.model() : drafts.model();
            log.info("reply drafts: Claude ({})", model);
            return new ClaudeReplyDrafter(new AnthropicModelClient(key.get()), model, drafts.maxTokens());
        }
        if ("claude".equals(drafts.writer())) {
            noKey("reply drafting");
            return new TemplateReplyDrafter("the app has no Claude API key (set ANTHROPIC_API_KEY or save it in "
                    + "~/.promise-graph/anthropic-key.txt, then restart)");
        }
        return new TemplateReplyDrafter("PG_DRAFTS_WRITER is set to template");
    }

    @Bean
    DraftStore draftStore(ObjectProvider<HikariDataSource> ds) {
        HikariDataSource d = ds.getIfAvailable();
        return d == null ? new InMemoryDraftStore() : new JdbcDraftStore(new NamedParameterJdbcTemplate(d));
    }

    @Bean
    Notifier notifier(InboxStore inbox) {
        return new InboxNotifier(inbox, Clock.systemUTC());
    }

    @Bean
    TimingPolicy timingPolicy() {
        return TimingPolicy.defaults();
    }

    /** The demo uses the simulated company; team mode uses the People page (active people only). */
    @Bean
    Directory directory(AuthProperties auth, ObjectProvider<TeamStore> team, PromiseGraphProperties props) {
        if (!auth.team()) {
            return new Directory(Company.PEOPLE);
        }
        TeamStore store = team.getObject();
        return new Directory(() -> store.members(props.tenant()).stream().filter(Member::active).map(Member::person).toList(),
                () -> store.members(props.tenant()).stream().filter(m -> m.active() && m.admin()).map(Member::email).toList());
    }

    // ------------------------------------------------------------ handoffs and the inbox

    @Bean
    HandoffPlanning.Planner handoffPlanner(HandoffProperties handoff, PromiseGraphProperties props, Environment env) {
        HandoffPlanning.Planner rules = new HandoffPlanning.RulePlanner();
        if ("agent".equals(handoff.planner())) {
            Optional<String> key = claudeKey(env);
            if (key.isPresent()) {
                return new HandoffAgent(new AnthropicModelClient(key.get()), props.model(), handoff.agentMaxSteps(), rules);
            }
            noKey("handoff planning");
        }
        return rules;
    }

    @Bean
    Visibility visibility(Directory directory, PrivacyProperties privacy, ObjectProvider<MailboxOwner> mailbox, AuthProperties auth,
            ObjectProvider<TeamStore> team, PromiseGraphProperties props) {
        if (auth.team()) {
            TeamStore store = team.getObject();
            // Team mode: each person sees only their own promises; admins come from the People page or PG_ADMINS.
            return Visibility.team(directory, viewer -> privacy.admins().contains(viewer)
                    || store.member(props.tenant(), viewer).map(Member::admin).orElse(false));
        }
        return new Visibility(directory, privacy.admins(),
                () -> Optional.ofNullable(mailbox.getIfAvailable()).flatMap(MailboxOwner::email));
    }

    // ------------------------------------------------------------ negotiations

    @Bean
    RecoverySettings recoverySettings(NegotiationProperties negotiation) {
        return new RecoverySettings(negotiation.capacity(), negotiation.minTeams(), negotiation.customers());
    }

    @Bean
    RecoveryPlanning.Planner recoveryPlanner(NegotiationProperties negotiation, PromiseGraphProperties props, Environment env) {
        RecoveryPlanning.Planner rules = new RulePlanner();
        if ("agent".equals(negotiation.planner())) {
            Optional<String> key = claudeKey(env);
            if (key.isPresent()) {
                return new OptionAgent(new AnthropicModelClient(key.get()), props.model(), negotiation.agentMaxSteps(), rules);
            }
            noKey("recovery planning");
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
