package dev.promisegraph.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Encryption of workflow data sent to Temporal, bound from {@code promisegraph.payload-encryption.*}.
 *
 * @param enabled encrypt workflow data (default true). Turning it off after it has been on breaks
 *     every workflow whose history is encrypted, so only switch it off on a fresh Temporal.
 * @param key the current key, base64 of 32 random bytes; if empty, {@code keyFile} is used
 * @param oldKeys retired keys, comma-separated base64, kept so older history can still be read
 * @param keyFile where the key lives when {@code key} is empty; created on first start
 * @param codecOrigins web pages allowed to call the codec server from a browser (the Temporal UI)
 */
@ConfigurationProperties("promisegraph.payload-encryption")
public record PayloadEncryptionProperties(Boolean enabled, String key, String oldKeys, String keyFile, List<String> codecOrigins) {

    public PayloadEncryptionProperties {
        enabled = enabled == null || enabled;
        key = key == null ? "" : key.strip();
        oldKeys = oldKeys == null ? "" : oldKeys.strip();
        keyFile = keyFile == null || keyFile.isBlank() ? System.getProperty("user.home") + "/.promise-graph/payload.key" : keyFile.strip();
        codecOrigins = codecOrigins == null || codecOrigins.isEmpty() ? List.of("http://localhost:8080")
                : codecOrigins.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
