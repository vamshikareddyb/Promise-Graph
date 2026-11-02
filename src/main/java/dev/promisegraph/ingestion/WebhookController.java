package dev.promisegraph.ingestion;

import dev.promisegraph.config.PromiseGraphProperties;
import dev.promisegraph.domain.Message;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts chat messages: {@code POST /messages} with JSON {id, channel, sender, ts, text}
 * and {@code Authorization: Bearer <token>}. Responds 202 once the message is buffered.
 * The Slack adapter (a later task) translates Slack events into this shape.
 */
@RestController
public class WebhookController {

    /** The request body. Fields are validated by hand so errors name exactly what is wrong. */
    public record IncomingMessage(String id, String channel, String sender, String ts, String text) {}

    private final IngestionGateway gateway;
    private final PromiseGraphProperties props;

    public WebhookController(IngestionGateway gateway, PromiseGraphProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @PostMapping("/messages")
    public ResponseEntity<Map<String, String>> receive(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody IncomingMessage body) {
        String token = props.webhookToken();
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "webhook is not configured"));
        }
        String expected = "Bearer " + token;
        if (authorization == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }

        List<String> missing = new ArrayList<>();
        if (blank(body.id())) missing.add("id");
        if (blank(body.channel())) missing.add("channel");
        if (blank(body.sender())) missing.add("sender");
        if (blank(body.ts())) missing.add("ts");
        if (blank(body.text())) missing.add("text");
        if (!missing.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing or empty fields: " + String.join(", ", missing)));
        }
        Message message = new Message(body.id(), body.channel(), body.sender(), body.ts(), body.text(), props.tenant());
        try {
            message.timestamp();
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "ts must be ISO 8601 with a time-zone offset"));
        }
        gateway.ingest(message);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("accepted", message.id()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
