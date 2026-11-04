package dev.promisegraph.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gmail REST API v1, read-only, for the signed-in user ("me"). */
public final class GmailRestApi implements GmailApi {

    private static final String BASE = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GoogleOAuth oauth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GmailRestApi(GoogleOAuth oauth) {
        this.oauth = oauth;
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonNode get(String pathAndQuery, boolean historyCall) {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + pathAndQuery))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + oauth.accessToken())
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new GmailApiException("could not reach Gmail: " + e.getMessage(), true, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GmailApiException("interrupted", true, e);
            }
            int status = response.statusCode();
            if (status == 401 && attempt == 0) {
                oauth.invalidate(); // access token expired early: refresh once and retry
                continue;
            }
            if (status == 404 && historyCall) {
                throw new HistoryExpiredException("Gmail history cursor is too old");
            }
            if (status == 429 || status >= 500) {
                throw new GmailApiException("Gmail returned " + status, true, null);
            }
            if (status >= 400) {
                throw new GmailApiException("Gmail rejected the request (" + status + ")", false, null);
            }
            try {
                return MAPPER.readTree(response.body());
            } catch (IOException e) {
                throw new GmailApiException("unreadable Gmail response", true, e);
            }
        }
        throw new GmailApiException("Gmail access was refused after refreshing the token", false, null);
    }

    @Override
    public Profile profile() {
        JsonNode n = get("/profile", false);
        return new Profile(n.path("emailAddress").asText(), n.path("historyId").asText());
    }

    @Override
    public HistoryPage history(String startHistoryId, String pageToken) {
        String path = "/history?historyTypes=messageAdded&startHistoryId=" + q(startHistoryId)
                + (pageToken == null ? "" : "&pageToken=" + q(pageToken));
        JsonNode n = get(path, true);
        List<HistoryRecord> records = new ArrayList<>();
        for (JsonNode h : n.path("history")) {
            List<String> ids = new ArrayList<>();
            for (JsonNode added : h.path("messagesAdded")) {
                ids.add(added.path("message").path("id").asText());
            }
            records.add(new HistoryRecord(h.path("id").asText(), ids));
        }
        return new HistoryPage(records, n.hasNonNull("nextPageToken") ? n.get("nextPageToken").asText() : null,
                n.path("historyId").asText(startHistoryId));
    }

    @Override
    public MessageList listMessages(String query, String pageToken, int maxResults) {
        String path = "/messages?maxResults=" + maxResults + "&q=" + q(query) + (pageToken == null ? "" : "&pageToken=" + q(pageToken));
        JsonNode n = get(path, false);
        List<String> ids = new ArrayList<>();
        n.path("messages").forEach(m -> ids.add(m.path("id").asText()));
        return new MessageList(ids, n.hasNonNull("nextPageToken") ? n.get("nextPageToken").asText() : null);
    }

    @Override
    public GmailMessage getMessage(String id) {
        return parseMessage(get("/messages/" + q(id) + "?format=full", false));
    }

    /** Pulls headers and the first text/plain and text/html bodies out of Gmail's MIME tree. */
    static GmailMessage parseMessage(JsonNode n) {
        JsonNode payload = n.path("payload");
        Map<String, String> headers = new LinkedHashMap<>();
        payload.path("headers").forEach(h -> headers.putIfAbsent(h.path("name").asText(), h.path("value").asText()));
        List<String> labels = new ArrayList<>();
        n.path("labelIds").forEach(l -> labels.add(l.asText()));
        String[] bodies = new String[2]; // [plain, html]
        collectBodies(payload, bodies);
        return new GmailMessage(n.path("id").asText(), n.path("threadId").asText(), labels,
                n.path("internalDate").asLong(), headers, bodies[0], bodies[1]);
    }

    private static void collectBodies(JsonNode part, String[] bodies) {
        String mime = part.path("mimeType").asText("");
        String data = part.path("body").path("data").asText(null);
        if (data != null && part.path("filename").asText("").isEmpty()) {
            if (bodies[0] == null && mime.equals("text/plain")) {
                bodies[0] = decode(data);
            } else if (bodies[1] == null && mime.equals("text/html")) {
                bodies[1] = decode(data);
            }
        }
        part.path("parts").forEach(child -> collectBodies(child, bodies));
    }

    static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }
}
