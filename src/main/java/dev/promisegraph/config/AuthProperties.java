package dev.promisegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How people sign in, bound from {@code promisegraph.auth.*}.
 *
 * @param mode {@code none} (default): the demo, where "Viewing as" picks anyone, or a single personal
 *     Gmail; {@code google}: team mode, where everyone signs in with Google and only sees their own promises
 * @param redirectUri where Google sends people back after signing in; must match the OAuth client
 * @param secretKey base64 32-byte key that encrypts stored sign-ins; if empty, a key file is created
 * @param secretKeyFile where that key file lives
 */
@ConfigurationProperties("promisegraph.auth")
public record AuthProperties(String mode, String redirectUri, String secretKey, String secretKeyFile) {

    public AuthProperties {
        mode = mode == null || mode.isBlank() ? "none" : mode.strip();
        if (!mode.equals("none") && !mode.equals("google")) {
            throw new IllegalArgumentException("promisegraph.auth.mode must be 'none' or 'google', got " + mode);
        }
        redirectUri = redirectUri == null || redirectUri.isBlank() ? "http://localhost:8787/login/callback" : redirectUri.strip();
        secretKeyFile = secretKeyFile == null || secretKeyFile.isBlank()
                ? System.getProperty("user.home") + "/.promise-graph/secret.key" : secretKeyFile;
    }

    public boolean team() {
        return mode.equals("google");
    }
}
