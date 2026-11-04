package dev.promisegraph.gmail;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Gmail account connected to this app: the one person using a personal setup. Looked up from
 * Gmail on first use and remembered; if Gmail can't be reached yet, the next call tries again.
 */
public class MailboxOwner {

    private static final Logger log = LoggerFactory.getLogger(MailboxOwner.class);

    private final Supplier<String> lookup;
    private volatile String email;

    public MailboxOwner(Supplier<String> lookup) {
        this.lookup = lookup;
    }

    public static MailboxOwner fixed(String email) {
        return new MailboxOwner(() -> email);
    }

    /** The account's address, lower-cased like the senders of mail; empty if Gmail can't be reached. */
    public Optional<String> email() {
        if (email == null) {
            try {
                String found = lookup.get();
                if (found != null && !found.isBlank()) {
                    email = found.strip().toLowerCase(Locale.ROOT);
                }
            } catch (RuntimeException e) {
                log.warn("could not ask Gmail which account is connected: {}", e.getMessage());
            }
        }
        return Optional.ofNullable(email);
    }
}
