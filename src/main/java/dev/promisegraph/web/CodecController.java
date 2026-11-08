package dev.promisegraph.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import dev.promisegraph.codec.EncryptionCodec;
import dev.promisegraph.config.AuthProperties;
import dev.promisegraph.config.PayloadEncryptionProperties;
import dev.promisegraph.privacy.Visibility;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.codec.PayloadCodecException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The codec server: lets the Temporal UI show encrypted workflow data. The UI's page, running in
 * your browser, posts the encrypted payloads here and gets them back readable (or the reverse, when
 * you send a signal or start a workflow from the UI). The key never leaves this app.
 *
 * <p>Endpoints, both taking and returning {@code {"payloads": [{"metadata": {...}, "data": "..."}]}}
 * with every value base64, as the Temporal UI sends them:
 * <ul>
 *   <li>{@code POST /codec/decode}: encrypted payloads in, readable payloads out</li>
 *   <li>{@code POST /codec/encode}: readable payloads in, encrypted payloads out</li>
 * </ul>
 *
 * <p>Who may call it: in team mode, a signed-in lead or admin (the UI sends the session cookie with
 * "include credentials" on); otherwise only a browser on this computer, like the rest of the app.
 * Browsers may only call it from the pages in {@code PG_CODEC_ORIGINS} (the Temporal UI).
 */
@RestController
@RequestMapping("/codec")
public class CodecController {

    private static final Logger log = LoggerFactory.getLogger(CodecController.class);
    /** The UI decodes a page of history at a time; this bounds one request's work. */
    static final int MAX_PAYLOADS = 2000;

    private final ObjectProvider<EncryptionCodec> codec;
    private final AuthProperties auth;
    private final Visibility visibility;
    private final ObjectMapper json = new ObjectMapper();

    public CodecController(ObjectProvider<EncryptionCodec> codec, AuthProperties auth, Visibility visibility) {
        this.codec = codec;
        this.auth = auth;
        this.visibility = visibility;
    }

    @PostMapping(value = "/decode", consumes = "application/json", produces = "application/json")
    public ResponseEntity<JsonNode> decode(@RequestBody JsonNode body, HttpServletRequest request) {
        return handle(body, request, "decode", c -> c::decode);
    }

    @PostMapping(value = "/encode", consumes = "application/json", produces = "application/json")
    public ResponseEntity<JsonNode> encode(@RequestBody JsonNode body, HttpServletRequest request) {
        return handle(body, request, "encode", c -> c::encode);
    }

    private ResponseEntity<JsonNode> handle(JsonNode body, HttpServletRequest request, String what,
            Function<EncryptionCodec, Function<List<Payload>, List<Payload>>> op) {
        EncryptionCodec c = codec.getIfAvailable();
        if (c == null) {
            return error(HttpStatus.NOT_FOUND, "payload encryption is off, so there is nothing to " + what);
        }
        String refusal = refusal(request);
        if (refusal != null) {
            log.warn("codec server: refused a {} request from {}: {}", what, request.getRemoteAddr(), refusal);
            return error(auth.team() && request.getSession(false) == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN, refusal);
        }
        List<Payload> in;
        try {
            in = fromJson(body);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        try {
            return ResponseEntity.ok(toJson(op.apply(c).apply(in)));
        } catch (PayloadCodecException e) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    /** Why this caller may not use the codec, or null if they may. */
    String refusal(HttpServletRequest request) {
        if (auth.team()) {
            HttpSession session = request.getSession(false);
            Object user = session == null ? null : session.getAttribute(AuthController.USER);
            if (user == null) {
                return "sign in to Promise Graph first (and turn on \"include cross-origin credentials\" in the Temporal UI)";
            }
            return visibility.canOperate(user.toString()) ? null : "only team leads and admins can read workflow data";
        }
        return isLoopback(request.getRemoteAddr()) ? null : "the codec server only answers this computer";
    }

    static boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Temporal's JSON form of payloads: metadata values and data are base64. */
    static List<Payload> fromJson(JsonNode body) {
        JsonNode list = body == null ? null : body.get("payloads");
        if (list == null || !list.isArray()) {
            throw new IllegalArgumentException("expected {\"payloads\": [...]}");
        }
        if (list.size() > MAX_PAYLOADS) {
            throw new IllegalArgumentException("at most " + MAX_PAYLOADS + " payloads per request");
        }
        List<Payload> out = new ArrayList<>(list.size());
        for (JsonNode p : list) {
            Payload.Builder b = Payload.newBuilder();
            JsonNode meta = p.path("metadata");
            for (Iterator<Map.Entry<String, JsonNode>> it = meta.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                b.putMetadata(e.getKey(), ByteString.copyFrom(base64(e.getValue().asText())));
            }
            b.setData(ByteString.copyFrom(base64(p.path("data").asText(""))));
            out.add(b.build());
        }
        return out;
    }

    ObjectNode toJson(List<Payload> payloads) {
        ObjectNode body = json.createObjectNode();
        ArrayNode list = body.putArray("payloads");
        Base64.Encoder b64 = Base64.getEncoder();
        for (Payload p : payloads) {
            ObjectNode node = list.addObject();
            ObjectNode meta = node.putObject("metadata");
            p.getMetadataMap().forEach((k, v) -> meta.put(k, b64.encodeToString(v.toByteArray())));
            node.put("data", b64.encodeToString(p.getData().toByteArray()));
        }
        return body;
    }

    /** Standard or URL-safe base64, padded or not: protobuf's JSON form accepts all of them. */
    private static byte[] base64(String text) {
        String t = text.strip();
        try {
            return Base64.getDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getUrlDecoder().decode(t);
            } catch (IllegalArgumentException e2) {
                throw new IllegalArgumentException("a payload value isn't base64");
            }
        }
    }

    private ResponseEntity<JsonNode> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(json.createObjectNode().put("error", message));
    }

    /**
     * Lets the Temporal UI's page (another origin, e.g. http://localhost:8080) call /codec from the
     * browser, cookies included, and nothing else. Spring answers the browser's preflight request.
     */
    @Configuration
    static class Cors {
        @Bean
        WebMvcConfigurer codecCors(PayloadEncryptionProperties enc) {
            return new WebMvcConfigurer() {
                @Override
                public void addCorsMappings(CorsRegistry registry) {
                    registry.addMapping("/codec/**")
                            .allowedOrigins(enc.codecOrigins().toArray(String[]::new))
                            .allowedMethods("POST")
                            .allowedHeaders("*")
                            .allowCredentials(true)
                            .maxAge(3600);
                }
            };
        }
    }
}
