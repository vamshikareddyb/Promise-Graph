package dev.promisegraph.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.promisegraph.domain.Commitment;
import dev.promisegraph.domain.CommitmentKind;
import dev.promisegraph.domain.Message;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a short, specific title for each new promise with a free model on this computer, through
 * Ollama (https://ollama.com). The built-in rules can only cut the sentence down ("attend a
 * meeting"); a model reads the email and names the actual task ("Attend the 4 pm meeting in the
 * main boardroom").
 *
 * <p>Best effort: if Ollama isn't running, the model is missing, or the answer is unusable, the
 * rule-made title stays. A refused connection pauses titling for a minute so a mailbox sync isn't
 * slowed down by one failed call per promise.
 */
public final class OllamaTitler {

    private static final Logger log = LoggerFactory.getLogger(OllamaTitler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
            You write short titles for promises found in emails. Reply with the title only: one line of 3 to 10 \
            words, starting with a verb, saying exactly what has to be done and the specific document, task, \
            person or place involved. Use the email's own words for names and things. Leave out the due date \
            (it is shown separately). No quotation marks, no full stop, no explanation.""";

    /** One extraction activity has two minutes; titles must leave time for the rest of it. */
    private static final Duration BUDGET = Duration.ofSeconds(75);
    private static final Duration PER_CALL = Duration.ofSeconds(40);
    private static final Duration PAUSE_WHEN_DOWN = Duration.ofMinutes(1);
    private static final int MAX_WORDS = 14;
    private static final int EMAIL_CHARS = 1500;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl;
    private final String model;
    private volatile Instant pausedUntil = Instant.MIN;

    public OllamaTitler(String baseUrl, String model) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    public String model() {
        return model;
    }

    /**
     * The commitments with new titles for the new promises {@code which} accepts. Slips and
     * completions keep their text: it is what matches them to an existing promise.
     */
    public List<Commitment> retitle(List<Commitment> found, List<Message> messages, Predicate<Commitment> which) {
        Map<String, Message> byId = messages.stream().collect(Collectors.toMap(Message::id, Function.identity(), (a, b) -> a));
        Instant deadline = Instant.now().plus(BUDGET);
        List<Commitment> out = new ArrayList<>(found.size());
        for (Commitment c : found) {
            if (c.kind() != CommitmentKind.NEW || !which.test(c) || Instant.now().isAfter(deadline) || Instant.now().isBefore(pausedUntil)) {
                out.add(c);
                continue;
            }
            Optional<String> title = title(c, byId.get(c.messageId()));
            out.add(title.map(t -> new Commitment(c.kind(), c.messageId(), c.owner(), t, c.quote(), c.confidence(),
                    c.channel(), c.recipient(), c.due(), c.shiftDays())).orElse(c));
        }
        return out;
    }

    Optional<String> title(Commitment c, Message message) {
        try {
            return clean(ask(request(c, message)));
        } catch (ConnectException e) {
            pausedUntil = Instant.now().plus(PAUSE_WHEN_DOWN);
            log.warn("Ollama isn't running at {}, so promise titles come from the built-in rules for now "
                    + "(open the Ollama app, or run: ollama serve)", baseUrl);
        } catch (HttpTimeoutException e) {
            log.warn("the local model {} took too long to title a promise; kept the rule-made title", model);
        } catch (IOException | RuntimeException e) {
            log.warn("could not title a promise with {} ({}); kept the rule-made title", model, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    ObjectNode request(Commitment c, Message message) {
        ObjectNode body = MAPPER.createObjectNode().put("model", model).put("stream", false);
        // Room for models that think before answering; the title itself is a few words.
        body.putObject("options").put("num_predict", 300).put("temperature", 0.2);
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        msgs.addObject().put("role", "user").put("content", prompt(c, message));
        return body;
    }

    static String prompt(Commitment c, Message message) {
        boolean request = Requests.ADDRESSEE.equals(c.owner());
        String email = message == null || message.text() == null ? "" : message.text();
        if (email.length() > EMAIL_CHARS) {
            email = email.substring(0, EMAIL_CHARS) + " …";
        }
        return "Email" + (message == null || message.sender() == null ? "" : " from " + message.sender()) + ":\n"
                + email + "\n\n"
                + "The promise is in this sentence: \"" + c.quote() + "\"\n"
                + (request ? "It is something the reader of the email was asked to do.\n"
                           : "It is something the writer of the email said they would do.\n")
                + "Title:";
    }

    private String ask(ObjectNode body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(PER_CALL)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) {
            pausedUntil = Instant.now().plus(PAUSE_WHEN_DOWN);
            throw new IllegalStateException("the model " + model + " isn't downloaded yet (run: ollama pull " + model + ")");
        }
        if (res.statusCode() >= 400) {
            throw new IllegalStateException("Ollama returned " + res.statusCode());
        }
        JsonNode reply = MAPPER.readTree(res.body());
        return reply.path("message").path("content").asText("");
    }

    /** One clean line, or empty when the answer isn't a usable title. */
    static Optional<String> clean(String raw) {
        String text = raw == null ? "" : raw.replaceAll("(?s)<think>.*?</think>", "").strip();
        String line = text.lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        line = line.replaceFirst("(?i)^(?:\\*\\*)?title(?:\\*\\*)?\\s*:\\s*", "")
                .replaceAll("^[\"'“‘*`]+|[\"'”’*`]+$", "")
                .replaceAll("[.!]+$", "")
                .replaceAll("\\s+", " ")
                .strip();
        int words = line.isEmpty() ? 0 : line.split(" ").length;
        if (words < 2 || words > MAX_WORDS) {
            return Optional.empty();
        }
        return Optional.of(Character.toUpperCase(line.charAt(0)) + line.substring(1));
    }
}
