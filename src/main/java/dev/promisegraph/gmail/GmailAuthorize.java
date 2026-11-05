package dev.promisegraph.gmail;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * One-time sign-in for a personal Gmail account: {@code ./gradlew gmailAuth}.
 *
 * <p>Prints a Google sign-in link, receives the answer on a temporary local address, and saves a
 * refresh token to the token file. Uses PKCE and a random state value, and asks only for read access.
 */
public final class GmailAuthorize {

    private GmailAuthorize() {}

    /** A random URL-safe string, for PKCE verifiers and state values. */
    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String authUrl(String clientId, String redirectUri, String state, String codeChallenge) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", GoogleOAuth.SCOPE);
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        return GoogleOAuth.AUTH_ENDPOINT + "?" + GoogleOAuth.encodeForm(params);
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path credentials = Path.of(arg(args, "--credentials", System.getProperty("user.home") + "/.promise-graph/gmail-credentials.json"));
        Path tokenFile = Path.of(arg(args, "--token", System.getProperty("user.home") + "/.promise-graph/gmail-token.json"));

        GoogleOAuth.ClientSecrets secrets = GoogleOAuth.ClientSecrets.load(credentials);
        String state = random(24);
        String verifier = random(48);
        CompletableFuture<Map<String, String>> answer = new CompletableFuture<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> params = parseQuery(exchange.getRequestURI().getRawQuery());
            if (!params.containsKey("code") && !params.containsKey("error")) {
                exchange.sendResponseHeaders(404, -1); // e.g. the browser asking for a favicon
                exchange.close();
                return;
            }
            boolean ok = params.containsKey("code") && state.equals(params.get("state"));
            byte[] page = (ok ? "Signed in. You can close this tab and return to the terminal."
                    : "Sign-in did not complete. Return to the terminal for details.").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, page.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(page);
            }
            answer.complete(params);
        });
        server.start();
        String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort();

        try {
            String link = authUrl(secrets.clientId(), redirectUri, state, challenge(verifier));
            if (openBrowser(link)) {
                System.out.println("Your browser should now show Google's sign-in page. If it didn't, open this link:\n");
            } else {
                System.out.println("Open this link in your browser and sign in with the Gmail account to read:\n");
            }
            System.out.println(link);
            System.out.println("\nWaiting for you to finish signing in (5 minutes). Don't press Ctrl+C here: it stops the sign-in.");

            Map<String, String> params = answer.get(5, TimeUnit.MINUTES);
            if (params.containsKey("error")) {
                throw new IOException("Google returned: " + params.get("error"));
            }
            if (!state.equals(params.get("state"))) {
                throw new IOException("state mismatch: the sign-in response was not for this request");
            }
            GoogleOAuth oauth = new GoogleOAuth(secrets, tokenFile);
            oauth.exchangeCodeAndSave(params.get("code"), redirectUri, verifier);
            String account = new GmailRestApi(oauth).profile().emailAddress();
            System.out.println("\nSigned in as " + account + ". Read-only token saved to " + tokenFile);
        } finally {
            server.stop(0);
        }
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    /**
     * Opens the sign-in page in the default browser, so nobody has to copy a long link out of a
     * terminal (in PowerShell, Ctrl+C stops the program instead of copying). Returns false where
     * there is no desktop, e.g. over SSH; the link is printed either way.
     */
    static boolean openBrowser(String link) {
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(link));
                return true;
            }
        } catch (IOException | RuntimeException e) {
            // fall back to the printed link
        }
        return false;
    }
}
