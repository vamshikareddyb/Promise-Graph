package dev.promisegraph.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.promisegraph.domain.Message;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The labelled message set. Each line holds a message and its label:
 * {@code {"kind": "new"|"slip"|"done"|null, "due": "YYYY-MM-DD"|null}}.
 */
public final class Dataset {

    public record Label(String kind, String due) {}

    public record LabelledMessage(Message message, Label label) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOURCE = "/sim/messages.jsonl";

    private Dataset() {}

    public static List<LabelledMessage> load(String tenant) {
        try (InputStream in = Dataset.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), tenant);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<LabelledMessage> parse(BufferedReader reader, String tenant) throws IOException {
        List<LabelledMessage> out = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = MAPPER.readTree(line);
            JsonNode label = n.path("label");
            out.add(new LabelledMessage(
                    new Message(n.get("id").asText(), n.get("channel").asText(), n.get("sender").asText(),
                            n.get("ts").asText(), n.get("text").asText(), tenant),
                    new Label(label.path("kind").isNull() ? null : label.path("kind").asText(null),
                            label.path("due").isNull() ? null : label.path("due").asText(null))));
        }
        out.sort(Comparator.comparing((LabelledMessage m) -> m.message().ts()).thenComparing(m -> m.message().id()));
        return out;
    }
}
