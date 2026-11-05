package dev.promisegraph.drafts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.promisegraph.drafts.DraftContracts.DraftContext;
import dev.promisegraph.drafts.DraftContracts.Written;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * The reply-draft agent on a free model running on this computer through Ollama
 * (https://ollama.com): no API key, and the email never leaves the machine. Same prompt and
 * context as {@link ClaudeReplyDrafter}.
 */
public final class OllamaReplyDrafter implements ReplyDrafter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String model;
    private final int maxTokens;

    public OllamaReplyDrafter(String baseUrl, String model, int maxTokens) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public String model() {
        return model;
    }

    /** "ollama:llama3.2", stored with the draft so the page can say who wrote it. */
    public String by() {
        return "ollama:" + model;
    }

    ObjectNode request(DraftContext ctx) {
        ObjectNode body = MAPPER.createObjectNode().put("model", model).put("stream", false);
        body.putObject("options").put("num_predict", maxTokens).put("temperature", 0.4);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", ClaudeReplyDrafter.SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", ClaudeReplyDrafter.payload(ctx).toString());
        return body;
    }

    @Override
    public Written write(DraftContext ctx) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                // Small models on a laptop CPU can take a while for a long thread.
                .timeout(Duration.ofSeconds(150))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request(ctx).toString()))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new IllegalStateException("Ollama isn't running at " + baseUrl + " (open the Ollama app, or run: ollama serve)", e);
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("the local model " + model + " took too long to answer", e);
        } catch (IOException e) {
            throw new IllegalStateException("could not reach Ollama at " + baseUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (res.statusCode() == 404) {
            throw new IllegalStateException("Ollama returned 404: the model " + model + " isn't downloaded yet (run: ollama pull " + model + ")");
        }
        if (res.statusCode() >= 400) {
            String why = "";
            try {
                why = MAPPER.readTree(res.body()).path("error").asText("");
            } catch (IOException | RuntimeException ignored) {
                // not JSON
            }
            throw new IllegalStateException("Ollama returned " + res.statusCode() + (why.isBlank() ? "" : " (" + why + ")"));
        }
        JsonNode reply;
        try {
            reply = MAPPER.readTree(res.body());
        } catch (IOException e) {
            throw new IllegalStateException("unreadable answer from Ollama", e);
        }
        String body = ClaudeReplyDrafter.clean(stripThinking(reply.path("message").path("content").asText("")));
        if (body.isBlank()) {
            throw new IllegalStateException("the local model returned an empty draft");
        }
        return new Written(body, by());
    }

    /** Some local models (e.g. qwen3, deepseek-r1) think aloud in <think> tags before answering. */
    static String stripThinking(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").strip();
    }
}
