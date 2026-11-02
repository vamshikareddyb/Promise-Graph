package dev.promisegraph.extraction.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Ids;
import dev.promisegraph.domain.Message;
import dev.promisegraph.extraction.ExtractionOutputException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates model output. Nothing a model returns is trusted until it passes here:
 * malformed output is rejected as a whole (and the call retried); individual commitments
 * that are not grounded in their source message are dropped.
 */
public final class ModelOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelOutputParser() {}

    public static boolean quoteInSource(String quote, String text) {
        return Ids.normalize(text).contains(Ids.normalize(quote));
    }

    private static String stripFences(String raw) {
        String t = raw.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*", "");
            t = t.replaceFirst("\\s*```$", "");
        }
        return t;
    }

    private static String requiredText(JsonNode node, String field, int min, int max) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) {
            throw new ExtractionOutputException("missing or non-text field: " + field);
        }
        String s = v.asText().strip();
        if (s.length() < min || s.length() > max) {
            throw new ExtractionOutputException("field " + field + " must be " + min + "-" + max + " characters");
        }
        return s;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isTextual()) {
            throw new ExtractionOutputException("field " + field + " must be text or null");
        }
        return v.asText();
    }

    public static List<Commitment> parse(String raw, List<Message> messages, Set<String> directory) {
        JsonNode root;
        try {
            root = MAPPER.readTree(stripFences(raw));
        } catch (JsonProcessingException e) {
            throw new ExtractionOutputException("model output is not JSON", e);
        }
        JsonNode items = root == null ? null : root.get("commitments");
        if (items == null || !items.isArray()) {
            throw new ExtractionOutputException("model output has no 'commitments' array");
        }

        Map<String, Message> byId = messages.stream().collect(Collectors.toMap(Message::id, Function.identity(), (a, b) -> a));
        List<Commitment> results = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw new ExtractionOutputException("each commitment must be an object");
            }
            String messageId = requiredText(item, "message_id", 1, 200);
            String kindText = requiredText(item, "kind", 3, 4).toUpperCase(Locale.ROOT);
            CommitmentKind kind;
            try {
                kind = CommitmentKind.valueOf(kindText);
            } catch (IllegalArgumentException e) {
                throw new ExtractionOutputException("unknown kind: " + kindText, e);
            }
            String owner = requiredText(item, "owner", 1, 100);
            String what = requiredText(item, "what", 3, 240);
            String quote = requiredText(item, "quote", 3, 500);
            JsonNode conf = item.get("confidence");
            if (conf == null || !conf.isNumber() || conf.asDouble() < 0 || conf.asDouble() > 1) {
                throw new ExtractionOutputException("confidence must be a number between 0 and 1");
            }
            String due = optionalText(item, "due");
            if (due != null) {
                try {
                    due = LocalDate.parse(due).toString();
                } catch (DateTimeParseException e) {
                    throw new ExtractionOutputException("due must be YYYY-MM-DD", e);
                }
            }
            Integer shiftDays = null;
            JsonNode shift = item.get("shift_days");
            if (shift != null && !shift.isNull()) {
                if (!shift.isInt() || shift.asInt() < 1 || shift.asInt() > 365) {
                    throw new ExtractionOutputException("shift_days must be an integer from 1 to 365");
                }
                shiftDays = shift.asInt();
            }

            Message message = byId.get(messageId);
            if (message == null || !quoteInSource(quote, message.text())) {
                continue; // refers to a message we did not send, or not grounded in the text
            }
            results.add(new Commitment(kind, message.id(), directory.contains(owner) ? owner : message.sender(),
                    what, quote, conf.asDouble(), message.channel(), optionalText(item, "recipient"), due, shiftDays));
        }
        return results;
    }
}
