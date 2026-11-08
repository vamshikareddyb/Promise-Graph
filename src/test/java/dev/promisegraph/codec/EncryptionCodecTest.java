package dev.promisegraph.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.payload.codec.PayloadCodecException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EncryptionCodecTest {

    final EncryptionCodec codec = new EncryptionCodec(new PayloadCipher(PayloadCipherTest.key(7), List.of()));

    record Email(String sender, String text) {}

    @Test
    void temporalOnlyEverSeesCiphertext() {
        DataConverter converter = new CodecDataConverter(DefaultDataConverter.newDefaultInstance(), List.of(codec), true);
        Email email = new Email("ashwini@example.com", "Please be prepared for the presentation.");

        Payload stored = converter.toPayload(email).orElseThrow();

        assertThat(EncryptionCodec.isEncrypted(stored)).isTrue();
        assertThat(stored.getMetadataMap()).containsOnlyKeys("encoding", EncryptionCodec.KEY_ID);
        assertThat(stored.toByteString().toStringUtf8()).doesNotContain("presentation").doesNotContain("ashwini").doesNotContain("json/plain");
        assertThat(converter.fromPayload(stored, Email.class, Email.class)).isEqualTo(email);
    }

    @Test
    void historyFromBeforeEncryptionStillReads() {
        Payload plain = DefaultDataConverter.newDefaultInstance().toPayload("written before the codec").orElseThrow();
        assertThat(codec.decode(List.of(plain))).containsExactly(plain);
    }

    @Test
    void encodingIsNotAppliedTwice() {
        Payload once = codec.encode(List.of(DefaultDataConverter.newDefaultInstance().toPayload("x").orElseThrow())).get(0);
        assertThat(codec.encode(List.of(once))).containsExactly(once);
    }

    @Test
    void aStrangersKeyCannotOpenIt() {
        Payload sealed = codec.encode(List.of(DefaultDataConverter.newDefaultInstance().toPayload("secret").orElseThrow())).get(0);
        EncryptionCodec other = new EncryptionCodec(new PayloadCipher(PayloadCipherTest.key(8), List.of()));
        assertThatThrownBy(() -> other.decode(List.of(sealed))).isInstanceOf(PayloadCodecException.class);
        assertThat(Optional.of(codec.decode(List.of(sealed)).get(0))).isPresent();
    }
}
