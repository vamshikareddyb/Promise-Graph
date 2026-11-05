package dev.promisegraph.config;

import dev.promisegraph.gmail.GmailActivitiesImpl;
import dev.promisegraph.gmail.GmailApi;
import dev.promisegraph.gmail.GmailRestApi;
import dev.promisegraph.gmail.GmailSyncContracts;
import dev.promisegraph.gmail.GmailSyncContracts.SyncState;
import dev.promisegraph.gmail.GmailSyncWorkflow;
import dev.promisegraph.gmail.GoogleOAuth;
import dev.promisegraph.gmail.MailboxOwner;
import dev.promisegraph.team.SecretBox;
import dev.promisegraph.team.TeamMailboxes;
import dev.promisegraph.team.TeamStore;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gmail reading. Two setups:
 *
 * <ul>
 *   <li>Personal ({@code PG_GMAIL_ENABLED=true}): one mailbox, signed in with {@code gradlew gmailAuth}.
 *   <li>Team ({@code PG_AUTH=google}): everyone who signs in to the web app has their own mailbox read.
 * </ul>
 */
@Configuration
@ConditionalOnExpression("${promisegraph.gmail.enabled:false} or '${promisegraph.auth.mode:none}' == 'google'")
public class GmailConfig {

    private static final Logger log = LoggerFactory.getLogger(GmailConfig.class);

    @Bean
    GoogleOAuth.ClientSecrets googleClient(GmailProperties gmail) throws IOException {
        return GoogleOAuth.ClientSecrets.load(Path.of(gmail.credentialsFile()));
    }

    // ------------------------------------------------------------ personal: one mailbox

    @Bean
    @ConditionalOnExpression("'${promisegraph.auth.mode:none}' != 'google'")
    GmailApi gmailApi(GoogleOAuth.ClientSecrets secrets, GmailProperties gmail) {
        return new GmailRestApi(new GoogleOAuth(secrets, Path.of(gmail.tokenFile())));
    }

    /** With one Gmail connected, the app is personal: its one user is the connected account. */
    @Bean
    @ConditionalOnExpression("'${promisegraph.auth.mode:none}' != 'google'")
    MailboxOwner mailboxOwner(GmailApi api) {
        return new MailboxOwner(() -> api.profile().emailAddress());
    }

    // ------------------------------------------------------------ team: a mailbox per person

    @Bean
    @ConditionalOnProperty(prefix = "promisegraph.auth", name = "mode", havingValue = "google")
    TeamMailboxes teamMailboxes(GoogleOAuth.ClientSecrets secrets, TeamStore store, SecretBox box, PromiseGraphProperties props) {
        return new TeamMailboxes(props.tenant(), secrets, store, box);
    }

    @Bean
    GmailActivitiesImpl gmailActivities(ObjectProvider<GmailApi> personal, ObjectProvider<TeamMailboxes> team) {
        return new GmailActivitiesImpl(mailbox -> mailbox == null ? personal.getObject() : team.getObject().forMailbox(mailbox));
    }

    /** Starts the sync for a mailbox; if it is already running, it carries on from its cursor. */
    public static void startSync(WorkflowClient client, GmailProperties gmail, String tenant, String mailbox) {
        GmailSyncWorkflow wf = client.newWorkflowStub(GmailSyncWorkflow.class, WorkflowOptions.newBuilder()
                .setWorkflowId(GmailSyncContracts.workflowId(tenant, mailbox))
                .setTaskQueue(PromiseGraphProperties.TASK_QUEUE_MAIN)
                .build());
        int poll = (int) gmail.pollInterval().toSeconds();
        try {
            WorkflowClient.start(wf::run, mailbox == null ? SyncState.personal(tenant, poll, gmail.backfillDays())
                    : SyncState.forAccount(tenant, mailbox, poll, gmail.backfillDays()));
            log.info("Gmail sync started{}", mailbox == null ? "" : " for " + mailbox);
        } catch (WorkflowExecutionAlreadyStarted e) {
            // e.g. after signing in again: check now, which also ends a wait after access was refused
            client.newWorkflowStub(GmailSyncWorkflow.class, GmailSyncContracts.workflowId(tenant, mailbox)).syncNow();
            log.info("Gmail sync already running{}; asked it to check now", mailbox == null ? "" : " for " + mailbox);
        }
    }

    /** Team mode reads each person's own mailbox; a sync left over from the personal setup has nothing to read. */
    static void stopPersonalSync(WorkflowClient client, String tenant) {
        try {
            client.newUntypedWorkflowStub(GmailSyncContracts.workflowId(tenant, null))
                    .terminate("team mode reads each signed-in person's own mailbox instead");
            log.info("Stopped the personal Gmail sync; team mode reads each person's mailbox");
        } catch (RuntimeException e) {
            // not running: nothing to stop
        }
    }

    @Bean
    ApplicationRunner startGmailSync(WorkflowClient client, GmailProperties gmail, PromiseGraphProperties props, AuthProperties auth,
            ObjectProvider<TeamStore> team) {
        return args -> {
            if (!props.temporal().workerEnabled()) {
                return;
            }
            if (auth.team()) {
                stopPersonalSync(client, props.tenant());
                team.getObject().accounts(props.tenant()).forEach(m -> startSync(client, gmail, props.tenant(), m));
            } else {
                startSync(client, gmail, props.tenant(), null);
            }
        };
    }
}
