package dev.promisegraph.drafts;

import dev.promisegraph.domain.Message;
import java.util.Optional;

/**
 * Where an email-sourced message lives in Gmail, read back from the IDs the Gmail mapper gave it:
 * channel {@code email:<thread>} or {@code email:<mailbox>:<thread>}, and message ID
 * {@code gmail-<id>} or {@code gmail-<mailbox hash>-<id>}.
 *
 * @param mailbox the account it was read from in team mode; null for the single personal mailbox
 */
public record EmailRef(String mailbox, String threadId, String gmailId) {

    public static Optional<EmailRef> of(Message m) {
        return of(m.channel(), m.id());
    }

    public static Optional<EmailRef> of(String channel, String messageId) {
        if (channel == null || !channel.startsWith("email:") || messageId == null || !messageId.startsWith("gmail-")) {
            return Optional.empty();
        }
        String rest = channel.substring("email:".length());
        int colon = rest.lastIndexOf(':');
        String mailbox = colon < 0 ? null : rest.substring(0, colon);
        String thread = colon < 0 ? rest : rest.substring(colon + 1);
        String gmailId = messageId.substring(messageId.lastIndexOf('-') + 1);
        if (thread.isBlank() || gmailId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new EmailRef(mailbox, thread, gmailId));
    }
}
