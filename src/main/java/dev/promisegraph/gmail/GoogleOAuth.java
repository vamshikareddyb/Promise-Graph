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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Google OAuth for one Gmail account, read-only. The refresh token comes from a local file (the
 * personal setup, signed in with {@code gradlew gmailAuth}) or from the app's encrypted account
 * store (team mode, signed in through the web app). It never leaves the machine except to Google.
 */
public final class GoogleOAuth {

    public static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    static final URI AUTH_ENDPOINT = URI.create("https://accounts.google.com/o/oauth2/v2/auth");
    static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");
    static final URI USERINFO_ENDPOINT = URI.create("https://openidconnect.googleapis.com/v1/userinfo");
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

    /**
     * What the web sign-in learns: the account, its refresh token for reading Gmail later, and the
     * permissions granted. Google lets people untick individual permissions, so Gmail may be missing.
     */
    public record SignIn(String email, String name, String refreshToken, String scopes) {

        public boolean canReadGmail() {
            return scopes != null && java.util.Arrays.asList(scopes.split(" ")).contains(SCOPE);
        }
    }

    private final ClientSecrets secrets;
    private final Path tokenFile;
    private final Supplier<String> storedRefreshToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    public GoogleOAuth(ClientSecrets secrets, Path tokenFile) {
        this.secrets = secrets;
        this.tokenFile = tokenFile;
        this.storedRefreshToken = null;
    }

    /** For an account signed in through the web app; the supplier returns its decrypted refresh token, or null. */
    public GoogleOAuth(ClientSecrets secrets, Supplier<String> refreshToken) {
        this.secrets = secrets;
        this.tokenFile = null;
        this.storedRefreshToken = refreshToken;
    }

    /** The sign-in page for the web app: who you are, plus read-only Gmail. */
    public static String webSignInUrl(ClientSecrets secrets, String redirectUri, String state, String codeChallenge) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", secrets.clientId());
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", "openid email profile " + SCOPE);
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        return AUTH_ENDPOINT + "?" + encodeForm(params);
    }

    /** Completes a web sign-in: exchanges the code, then asks Google who signed in. */
    public SignIn completeWebSignIn(String code, String redirectUri, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", secrets.clientId());
        form.put("client_secret", secrets.clientSecret());
        form.put("redirect_uri", redirectUri);
        form.put("grant_type", "authorization_code");
        form.put("code_verifier", codeVerifier);
        JsonNode tokens = postForm(form);
        String access = tokens.path("access_token").asText(null);
        if (access == null) {
            throw new GmailApi.GmailApiException("Google returned no access token", false, null);
        }
        HttpRequest request = HttpRequest.newBuilder(USERINFO_ENDPOINT).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + access).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new GmailApi.GmailApiException("Google did not say who signed in (" + response.statusCode() + ")", false, null);
            }
            JsonNode info = MAPPER.readTree(response.body());
            if (!info.path("email_verified").asBoolean(false)) {
                throw new GmailApi.GmailApiException("that Google account's email address is not verified", false, null);
            }
            return new SignIn(info.path("email").asText(), info.path("name").asText(null), tokens.path("refresh_token").asText(null),
                    tokens.path("scope").asText(""));
        } catch (IOException e) {
            throw new GmailApi.GmailApiException("could not reach Google: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GmailApi.GmailApiException("interrupted", true, e);
        }
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
        if (storedRefreshToken != null) {
            refreshToken = storedRefreshToken.get();
            if (refreshToken == null) {
                throw new GmailApi.GmailApiException("this account has no Gmail sign-in; sign in to the web app again", false, null);
            }
        } else {
            try {
                refreshToken = MAPPER.readTree(Files.readString(tokenFile)).path("refresh_token").asText(null);
            } catch (IOException e) {
                throw new GmailApi.GmailApiException("no Gmail sign-in found at " + tokenFile + "; run ./gradlew gmailAuth", false, e);
            }
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
                throw new GmailApi.GmailApiException("Google rejected the token request (" + status + "); sign in again", false, null);
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
