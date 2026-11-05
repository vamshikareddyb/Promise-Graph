package dev.promisegraph.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic identifiers: the same input always gives the same ID, so a duplicate
 * event can never create a second promise or a second workflow.
 */
public final class Ids {

    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final String SEPARATOR = "\u001f";

    private Ids() {}

    public static String normalize(String text) {
        String lowered = text.toLowerCase(Locale.ROOT).replace('’', '\'');
        return SPACES.matcher(NON_WORD.matcher(lowered).replaceAll(" ")).replaceAll(" ").strip();
    }

    static String digest(int length, String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(String.join(SEPARATOR, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, length);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String promiseId(String tenant, String messageId, String owner, String what) {
        return tenant + "-" + digest(16, tenant, messageId, owner.toLowerCase(Locale.ROOT), normalize(what));
    }

    public static String promiseWorkflowId(String promiseId) {
        return "promise-" + promiseId;
    }

    public static String extractionWorkflowId(String tenant, List<String> messageIds) {
        List<String> parts = new ArrayList<>();
        parts.add(tenant);
        parts.addAll(messageIds.stream().sorted().toList());
        return "extract-" + tenant + "-" + digest(16, parts.toArray(String[]::new));
    }

    /** A short stable tag for a value, e.g. a mailbox in message IDs. */
    public static String hash(String value) {
        return digest(10, value.toLowerCase(Locale.ROOT));
    }

    public static String notificationKey(String... parts) {
        return digest(24, parts);
    }
}
