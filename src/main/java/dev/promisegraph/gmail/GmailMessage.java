package dev.promisegraph.gmail;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parts of a Gmail message Promise Graph needs, already decoded from the API's MIME structure.
 *
 * @param internalDate when Gmail received or sent it, epoch milliseconds
 * @param headers header names lower-cased
 */
public record GmailMessage(
        String id,
        String threadId,
        List<String> labelIds,
        long internalDate,
        Map<String, String> headers,
        String plainText,
        String html) {

    public GmailMessage {
        labelIds = labelIds == null ? List.of() : List.copyOf(labelIds);
        Map<String, String> lower = new TreeMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
        }
        headers = Map.copyOf(lower);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }
}
