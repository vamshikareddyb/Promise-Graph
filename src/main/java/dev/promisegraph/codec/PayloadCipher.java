package dev.promisegraph.codec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption of workflow data, with a key ring for rotation.
 *
 * <p>One key, the <em>current</em> key, encrypts everything new. Older keys only decrypt what they
 * once encrypted. Every sealed value says which key sealed it (its key id), so a rotation never
 * strands old workflow history.
 *
 * <p>Sealed bytes are {@code IV (12 bytes) || ciphertext || GCM tag (16 bytes)}. The key id is
 * bound in as additional authenticated data, so a value can't be relabelled with another key's id
 * and anything altered after sealing fails to open instead of decrypting to garbage.
 */
public final class PayloadCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String currentId;
    private final Map<String, SecretKeySpec> keys;

    /**
     * @param current the key that encrypts new data (32 bytes)
     * @param older keys from before a rotation, kept to read existing history
     */
    public PayloadCipher(byte[] current, Iterable<byte[]> older) {
        Map<String, SecretKeySpec> ring = new LinkedHashMap<>();
        this.currentId = idOf(current);
        ring.put(currentId, spec(current));
        for (byte[] k : older) {
            ring.putIfAbsent(idOf(k), spec(k));
        }
        this.keys = Map.copyOf(ring);
    }

    /**
     * The key ring from settings. The current key comes from {@code base64Key} or, if that is
     * empty, from {@code keyFile}, which is created with a fresh random key the first time and is
     * readable only by its owner. {@code olderBase64} lists retired keys, comma-separated.
     */
    public static PayloadCipher load(String base64Key, String olderBase64, Path keyFile) throws IOException {
        byte[] current = base64Key != null && !base64Key.isBlank() ? decodeKey(base64Key, "PG_PAYLOAD_KEY") : fromFile(keyFile);
        java.util.List<byte[]> older = new java.util.ArrayList<>();
        if (olderBase64 != null) {
            for (String k : olderBase64.split(",")) {
                if (!k.isBlank()) {
                    older.add(decodeKey(k, "PG_PAYLOAD_OLD_KEYS"));
                }
            }
        }
        return new PayloadCipher(current, older);
    }

    /** The id written next to every sealed value: the first 8 bytes of the key's SHA-256, in hex. */
    public static String idOf(byte[] key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key);
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String currentKeyId() {
        return currentId;
    }

    public int keyCount() {
        return keys.size();
    }

    /** Seals with the current key. A new random IV every time, so equal inputs never look equal. */
    public byte[] seal(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, keys.get(currentId), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(currentId.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = c.doFinal(plain);
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt workflow data", e);
        }
    }

    /**
     * Opens a value sealed by key {@code keyId}.
     *
     * @throws IllegalStateException if that key isn't in the ring, or the value was altered
     */
    public byte[] open(String keyId, byte[] sealed) {
        SecretKeySpec key = Optional.ofNullable(keyId).map(keys::get).orElseThrow(() -> new IllegalStateException(
                "workflow data was encrypted with key " + keyId + ", which this app doesn't have "
                        + "(add the old key to PG_PAYLOAD_OLD_KEYS)"));
        if (sealed.length < IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("encrypted workflow data is too short to be valid");
        }
        try {
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            c.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypted workflow data failed its integrity check (altered, or the wrong key)", e);
        }
    }

    private static SecretKeySpec spec(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("a payload key must be 32 bytes (AES-256), got " + key.length);
        }
        return new SecretKeySpec(key, "AES");
    }

    private static byte[] decodeKey(String base64, String setting) {
        try {
            byte[] key = Base64.getDecoder().decode(base64.strip());
            spec(key);
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(setting + " must be a base64 32-byte key: " + e.getMessage(), e);
        }
    }

    private static byte[] fromFile(Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            return decodeKey(Files.readString(keyFile), keyFile.toString());
        }
        byte[] fresh = new byte[32];
        RANDOM.nextBytes(fresh);
        Path parent = keyFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(fresh), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: the user profile folder protects it
        }
        return fresh;
    }
}
