package dev.promisegraph.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google OAuth for one personal Gmail account. Read-only scope. The refresh token is stored in a
 * local file readable only by its owner and never leaves the machine except to Google.
 */
public final class GoogleOAuth {

    public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    static final URI AUTH_ENDPOINT = URI.create("https://accounts.google.com/o/oauth2/v2/auth");
    static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The OAuth client from the credentials JSON downloaded from Google Cloud ("Desktop app"). */
    public record ClientSecrets(String clientId, String clientSecret) {

        public static ClientSecrets load(Path credentialsFile) throws IOException {
            JsonNode root = MAPPER.readTree(Files.readString(credentialsFile));
            JsonNode client = root.has("installed") ? root.get("installed") : root.get("web");
            if (client == null || !client.hasNonNull("client_id") || !client.hasNonNull("client_secret")) {
                throw new IOException("not a Google OAuth client file: " + credentialsFile);
            }
            return new ClientSecrets(client.get("client_id").asText(), client.get("client_secret").asText());
        }
    }

    private final ClientSecrets secrets;
    private final Path tokenFile;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    public GoogleOAuth(ClientSecrets secrets, Path tokenFile) {
        this.secrets = secrets;
        this.tokenFile = tokenFile;
    }

    /** A valid access token, refreshed with the stored refresh token when needed. */
    public synchronized String accessToken() {
        if (accessToken == null || Instant.now().isAfter(expiresAt.minusSeconds(60))) {
            refresh();
        }
        return accessToken;
    }

    /** Forces a refresh on the next call, e.g. after a 401. */
    public synchronized void invalidate() {
        accessToken = null;
    }

    private void refresh() {
        String refreshToken;
        try {
            refreshToken = MAPPER.readTree(Files.readString(tokenFile)).path("refresh_token").asText(null);
        } catch (IOException e) {
            throw new GmailApi.GmailApiException("no Gmail sign-in found at " + tokenFile + "; run ./gradlew gmailAuth", false, e);
        }
        if (refreshToken == null) {
            throw new GmailApi.GmailApiException("token file has no refresh_token; run ./gradlew gmailAuth again", false, null);
        }
        JsonNode body = postForm(Map.of(
                "client_id", secrets.clientId(),
                "client_secret", secrets.clientSecret(),
                "refresh_token", refreshToken,
                "grant_type", "refresh_token"));
        accessToken = body.path("access_token").asText();
        expiresAt = Instant.now().plusSeconds(body.path("expires_in").asLong(3600));
    }

    /** Exchanges an authorization code (from {@link GmailAuthorize}) and saves the refresh token. */
    void exchangeCodeAndSave(String code, String redirectUri, String codeVerifier) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", secrets.clientId());
        form.put("client_secret", secrets.clientSecret());
        form.put("redirect_uri", redirectUri);
        form.put("grant_type", "authorization_code");
        form.put("code_verifier", codeVerifier);
        JsonNode body = postForm(form);
        if (!body.hasNonNull("refresh_token")) {
            throw new IOException("Google returned no refresh token; remove the app's access at "
                    + "https://myaccount.google.com/permissions and sign in again");
        }
        ObjectNode saved = MAPPER.createObjectNode()
                .put("refresh_token", body.get("refresh_token").asText())
                .put("scope", body.path("scope").asText(SCOPE));
        writePrivate(tokenFile, saved.toPrettyString());
        accessToken = body.path("access_token").asText(null);
        expiresAt = Instant.now().plusSeconds(body.path("expires_in").asLong(3600));
    }

    static void writePrivate(Path file, String content) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: file permissions follow the user profile folder
        }
    }

    static String encodeForm(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private JsonNode postForm(Map<String, String> form) {
        HttpRequest request = HttpRequest.newBuilder(TOKEN_ENDPOINT)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 500 || status == 429) {
                throw new GmailApi.GmailApiException("Google sign-in service returned " + status, true, null);
            }
            if (status >= 400) {
                // invalid_grant: the user revoked access or the token expired; a new sign-in is needed
                throw new GmailApi.GmailApiException("Google rejected the token request (" + status + "); run ./gradlew gmailAuth again", false, null);
            }
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new GmailApi.GmailApiException("could not reach Google sign-in: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GmailApi.GmailApiException("interrupted", true, e);
        }
    }
}
