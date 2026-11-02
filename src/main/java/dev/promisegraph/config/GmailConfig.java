package dev.promisegraph.config;

import dev.promisegraph.gmail.GmailActivitiesImpl;
import dev.promisegraph.gmail.GmailApi;
import dev.promisegraph.gmail.GmailRestApi;
import dev.promisegraph.gmail.GmailSyncContracts.SyncState;
import dev.promisegraph.gmail.GmailSyncWorkflow;
import dev.promisegraph.gmail.GoogleOAuth;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Gmail reading, switched on with {@code promisegraph.gmail.enabled=true} (env {@code PG_GMAIL_ENABLED}). */
@Configuration
@ConditionalOnProperty(prefix = "promisegraph.gmail", name = "enabled", havingValue = "true")
public class GmailConfig {

    private static final Logger log = LoggerFactory.getLogger(GmailConfig.class);

    @Bean
    GmailApi gmailApi(GmailProperties gmail) throws IOException {
        var secrets = GoogleOAuth.ClientSecrets.load(Path.of(gmail.credentialsFile()));
        return new GmailRestApi(new GoogleOAuth(secrets, Path.of(gmail.tokenFile())));
    }

    @Bean
    GmailActivitiesImpl gmailActivities(GmailApi api) {
        return new GmailActivitiesImpl(api);
    }

    /** Starts the mailbox sync once; if it is already running, it simply carries on from its cursor. */
    @Bean
    ApplicationRunner startGmailSync(WorkflowClient client, GmailProperties gmail, PromiseGraphProperties props) {
        return args -> {
            if (!props.temporal().workerEnabled()) {
                return;
            }
            GmailSyncWorkflow wf = client.newWorkflowStub(GmailSyncWorkflow.class, WorkflowOptions.newBuilder()
                    .setWorkflowId("gmail-sync-" + props.tenant())
                    .setTaskQueue(PromiseGraphProperties.TASK_QUEUE_MAIN)
                    .build());
            try {
                WorkflowClient.start(wf::run, new SyncState(props.tenant(), null,
                        (int) gmail.pollInterval().toSeconds(), gmail.backfillDays(), 0));
                log.info("Gmail sync started");
            } catch (WorkflowExecutionAlreadyStarted e) {
                log.info("Gmail sync already running");
            }
        };
    }
}
