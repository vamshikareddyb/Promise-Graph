package dev.promisegraph.gmail;

import dev.promisegraph.domain.Message;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides which emails are worth reading and turns them into Promise Graph messages.
 * Sent and received mail are both kept: the promise always belongs to whoever wrote the email.
 */
public final class GmailMapper {

    /** Gmail labels that never contain a personal commitment worth tracking. */
    static final Set<String> SKIPPED_LABELS = Set.of(
            "SPAM", "TRASH", "DRAFT", "CHAT",
            "CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL", "CATEGORY_UPDATES", "CATEGORY_FORUMS");

    private static final Pattern ADDRESS = Pattern.compile("<([^>]+)>");
    private static final Pattern NO_REPLY = Pattern.compile("^(no-?reply|do-?not-?reply|notifications?|mailer-daemon)@", Pattern.CASE_INSENSITIVE);

    private GmailMapper() {}

    /** The bare address from a header like {@code "Neha Kapoor <neha@example.com>"}, lower-cased. */
    static String address(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher m = ADDRESS.matcher(header);
        String addr = m.find() ? m.group(1) : header;
        return addr.strip().toLowerCase(Locale.ROOT);
    }

    /** Why this email is skipped, or empty if it should be read. */
    static Optional<String> skipReason(GmailMessage m) {
        for (String label : m.labelIds()) {
            if (SKIPPED_LABELS.contains(label)) {
                return Optional.of("label " + label);
            }
        }
        if (m.header("List-Unsubscribe") != null || m.header("List-Id") != null) {
            return Optional.of("mailing list");
        }
        String from = address(m.header("From"));
        if (from == null) {
            return Optional.of("no sender");
        }
        if (NO_REPLY.matcher(from).find()) {
            return Optional.of("automated sender");
        }
        return Optional.empty();
    }

    /** The message to process, or empty if the email is skipped or has no text of its own. */
    public static Optional<Message> toMessage(GmailMessage m, String tenant) {
        if (skipReason(m).isPresent()) {
            return Optional.empty();
        }
        String text = EmailText.clean(m.plainText(), m.html());
        if (text.isBlank()) {
            return Optional.empty();
        }
        String ts = Instant.ofEpochMilli(m.internalDate()).atOffset(ZoneOffset.UTC).toString();
        // One batching channel per thread keeps a conversation's messages together.
        return Optional.of(new Message("gmail-" + m.id(), "email:" + m.threadId(), address(m.header("From")), ts, text, tenant));
    }
}
