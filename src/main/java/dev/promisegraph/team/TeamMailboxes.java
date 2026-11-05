package dev.promisegraph.team;

import dev.promisegraph.gmail.GmailApi;
import dev.promisegraph.gmail.GmailRestApi;
import dev.promisegraph.gmail.GoogleOAuth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One read-only Gmail client per signed-in account, using its stored (encrypted) sign-in. */
public final class TeamMailboxes {

    private final String tenant;
    private final GoogleOAuth.ClientSecrets secrets;
    private final TeamStore store;
    private final SecretBox box;
    private final Map<String, GmailApi> clients = new ConcurrentHashMap<>();

    public TeamMailboxes(String tenant, GoogleOAuth.ClientSecrets secrets, TeamStore store, SecretBox box) {
        this.tenant = tenant;
        this.secrets = secrets;
        this.store = store;
        this.box = box;
    }

    public GmailApi forMailbox(String email) {
        if (email == null) {
            throw new GmailApi.GmailApiException("team mode reads each person's own mailbox; this sync names none", false, null);
        }
        return clients.computeIfAbsent(email, e -> new GmailRestApi(new GoogleOAuth(secrets,
                () -> store.refreshToken(tenant, e).map(box::decrypt).orElse(null))));
    }

    /** After a new sign-in, the next call builds a fresh client with the new token. */
    public void forget(String email) {
        clients.remove(email);
    }
}
