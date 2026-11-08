package dev.promisegraph.codec;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.EncodingKeys;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Temporal payload codec that encrypts every workflow input, output, signal, query result,
 * activity argument and (with failure encoding on) error message before it leaves the app.
 *
 * <p>The whole original payload, its own metadata included, is serialized and sealed, so the
 * Temporal server stores neither the data nor what kind of data it is. The sealed payload carries
 * two labels in the clear: {@code encoding: binary/encrypted} and the id of the key that sealed it.
 *
 * <p>Payloads without the {@code binary/encrypted} label are passed through on decode untouched:
 * that is history written before encryption was turned on, which must keep replaying.
 */
public final class EncryptionCodec implements PayloadCodec {

    public static final String ENCODING = "binary/encrypted";
    public static final String KEY_ID = "encryption-key-id";
    private static final ByteString ENCODING_BYTES = ByteString.copyFrom(ENCODING, StandardCharsets.UTF_8);

    private final PayloadCipher cipher;

    public EncryptionCodec(PayloadCipher cipher) {
        this.cipher = cipher;
    }

    public PayloadCipher cipher() {
        return cipher;
    }

    @Override
    public List<Payload> encode(List<Payload> payloads) {
        return payloads.stream().map(this::encodeOne).toList();
    }

    @Override
    public List<Payload> decode(List<Payload> payloads) {
        return payloads.stream().map(this::decodeOne).toList();
    }

    Payload encodeOne(Payload p) {
        if (isEncrypted(p)) {
            return p; // already sealed: encoding twice would only make decoding need two passes
        }
        return Payload.newBuilder()
                .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, ENCODING_BYTES)
                .putMetadata(KEY_ID, ByteString.copyFrom(cipher.currentKeyId(), StandardCharsets.UTF_8))
                .setData(ByteString.copyFrom(cipher.seal(p.toByteArray())))
                .build();
    }

    Payload decodeOne(Payload p) {
        if (!isEncrypted(p)) {
            return p;
        }
        ByteString keyId = p.getMetadataOrDefault(KEY_ID, null);
        try {
            byte[] plain = cipher.open(keyId == null ? null : keyId.toStringUtf8(), p.getData().toByteArray());
            return Payload.parseFrom(plain);
        } catch (IllegalStateException | InvalidProtocolBufferException e) {
            throw new PayloadCodecException(e.getMessage(), e);
        }
    }

    public static boolean isEncrypted(Payload p) {
        return ENCODING_BYTES.equals(p.getMetadataOrDefault(EncodingKeys.METADATA_ENCODING_KEY, null));
    }
}
