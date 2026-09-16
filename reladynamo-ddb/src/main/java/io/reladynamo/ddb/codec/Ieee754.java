package io.reladynamo.ddb.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Lossless IEEE-754 payload encoding. DynamoDB {@code N} cannot represent NaN, infinities,
 * {@code Double.MAX_VALUE}, or {@code -0.0}, and decimalizes finite values; raw bits as {@code B}
 * round-trip every bit pattern including NaN payloads.
 */
final class Ieee754 {

    private Ieee754() {
    }

    static AttributeValue encodeDouble(double v) {
        long bits = Double.doubleToRawLongBits(v);
        byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putLong(bits);
        return AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build();
    }

    static double decodeDouble(AttributeValue av, String attributeName) {
        byte[] bytes = requireBinary(av, attributeName, 8);
        return Double.longBitsToDouble(
                ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong());
    }

    static AttributeValue encodeFloat(float v) {
        int bits = Float.floatToRawIntBits(v);
        byte[] bytes = new byte[4];
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(bits);
        return AttributeValue.builder().b(SdkBytes.fromByteArray(bytes)).build();
    }

    static float decodeFloat(AttributeValue av, String attributeName) {
        byte[] bytes = requireBinary(av, attributeName, 4);
        return Float.intBitsToFloat(
                ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt());
    }

    private static byte[] requireBinary(AttributeValue av, String attributeName, int expected) {
        if (av.b() == null) {
            throw new CodecException(
                    "attribute " + attributeName + " expected B (IEEE-754 bits), got " + av.type());
        }
        byte[] bytes = av.b().asByteArray();
        if (bytes.length != expected) {
            throw new CodecException(
                    "attribute " + attributeName + " IEEE-754 B must be " + expected
                            + " bytes, got " + bytes.length);
        }
        return bytes;
    }
}
