package dev.promisegraph.team;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts Google refresh tokens before they are stored (AES-256-GCM). The key comes from
 * {@code PG_SECRET_KEY} (32 bytes, base64) or, if unset, a key file created on first use next to
 * the Gmail credentials, readable only by its owner. Lose the key and everyone signs in again.
 */
public final class SecretBox {

    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretBox(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("the secret key must be 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** From a base64 key if given, else from (or into) the key file. */
    public static SecretBox load(String base64Key, Path keyFile) throws IOException {
        if (base64Key != null && !base64Key.isBlank()) {
            return new SecretBox(Base64.getDecoder().decode(base64Key.strip()));
        }
        if (Files.exists(keyFile)) {
            return new SecretBox(Base64.getDecoder().decode(Files.readString(keyFile).strip()));
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
        return new SecretBox(fresh);
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt", e);
        }
    }

    public String decrypt(String sealed) {
        try {
            byte[] in = Base64.getDecoder().decode(sealed);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, in, 0, IV_BYTES));
            return new String(c.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("could not decrypt a stored sign-in; the secret key changed?", e);
        }
    }
}
