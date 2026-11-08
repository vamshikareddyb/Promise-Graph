package dev.promisegraph.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PayloadCipherTest {

    static byte[] key(int fill) {
        byte[] k = new byte[32];
        java.util.Arrays.fill(k, (byte) fill);
        return k;
    }

    @Test
    void sealsAndOpensWithoutLeavingThePlainText() {
        PayloadCipher c = new PayloadCipher(key(1), List.of());
        byte[] plain = "Could you please send over the requirements document?".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = c.seal(plain);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("requirements");
        assertThat(c.open(c.currentKeyId(), sealed)).isEqualTo(plain);
        assertThat(c.seal(plain)).isNotEqualTo(sealed); // a fresh IV every time
    }

    @Test
    void refusesAlteredDataAndUnknownKeys() {
        PayloadCipher c = new PayloadCipher(key(1), List.of());
        byte[] sealed = c.seal("hello".getBytes(StandardCharsets.UTF_8));
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> c.open(c.currentKeyId(), sealed)).hasMessageContaining("integrity");
        assertThatThrownBy(() -> c.open("0000000000000000", c.seal(new byte[] {1}))).hasMessageContaining("PG_PAYLOAD_OLD_KEYS");
    }

    @Test
    void anOldKeyStillReadsWhatItSealedAfterRotation() {
        PayloadCipher before = new PayloadCipher(key(1), List.of());
        byte[] sealed = before.seal("old history".getBytes(StandardCharsets.UTF_8));
        PayloadCipher after = new PayloadCipher(key(2), List.of(key(1)));
        assertThat(after.currentKeyId()).isNotEqualTo(before.currentKeyId());
        assertThat(new String(after.open(before.currentKeyId(), sealed), StandardCharsets.UTF_8)).isEqualTo("old history");
    }

    @Test
    void createsTheKeyFileOnceAndReusesIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub/payload.key");
        PayloadCipher first = PayloadCipher.load("", "", file);
        assertThat(Base64.getDecoder().decode(Files.readString(file).strip())).hasSize(32);
        assertThat(PayloadCipher.load("", "", file).currentKeyId()).isEqualTo(first.currentKeyId());
        String env = Base64.getEncoder().encodeToString(key(3));
        assertThat(PayloadCipher.load(env, Base64.getEncoder().encodeToString(key(1)), file).keyCount()).isEqualTo(2);
        assertThatThrownBy(() -> PayloadCipher.load("c2hvcnQ=", "", file)).hasMessageContaining("PG_PAYLOAD_KEY");
    }
}
