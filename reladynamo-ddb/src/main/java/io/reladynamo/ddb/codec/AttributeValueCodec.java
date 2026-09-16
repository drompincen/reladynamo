package io.reladynamo.ddb.codec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.temporal.TemporalEncoder;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * One-attribute encode/decode. The item codec owns DynamoDB {@code NULL} / schema version; this
 * class only sees present, non-null Java values.
 */
final class AttributeValueCodec {

    private final Timestamp infinityOrNull;

    AttributeValueCodec(Timestamp infinityOrNull) {
        this.infinityOrNull = infinityOrNull;
    }

    AttributeValue encode(AttributeMapping mapping, AttributeKind kind, Object value) {
        String name = mapping.javaName();
        switch (kind) {
            case BOOLEAN:
                return AttributeValue.builder().bool(expect(name, value, Boolean.class)).build();
            case BYTE:
                return n(Byte.toString(expect(name, value, Byte.class)));
            case SHORT:
                return n(Short.toString(expect(name, value, Short.class)));
            case INT:
                return n(Integer.toString(expect(name, value, Integer.class)));
            case LONG:
                return n(Long.toString(expect(name, value, Long.class)));
            case FLOAT:
                return Ieee754.encodeFloat(expect(name, value, Float.class));
            case DOUBLE:
                return Ieee754.encodeDouble(expect(name, value, Double.class));
            case CHAR:
                return encodeChar(name, expect(name, value, Character.class));
            case STRING:
                return AttributeValue.builder().s(expect(name, value, String.class)).build();
            case SQL_DATE:
                return AttributeValue.builder()
                        .s(DateTimeWire.encodeSqlDate(expect(name, value, Date.class)))
                        .build();
            case SQL_TIMESTAMP:
                return encodeTimestamp(name, expect(name, value, Timestamp.class));
            case SQL_TIME:
                return AttributeValue.builder()
                        .s(DateTimeWire.encodeSqlTime(expect(name, value, Time.class)))
                        .build();
            case RELADOMO_TIME:
                return AttributeValue.builder()
                        .s(DateTimeWire.encodeReladomoTime(
                                expect(name, value, com.gs.fw.common.mithra.util.Time.class)))
                        .build();
            case BIG_DECIMAL:
                return encodeBigDecimal(name, expect(name, value, BigDecimal.class));
            case BYTE_ARRAY:
                byte[] bytes = expect(name, value, byte[].class);
                return AttributeValue.builder()
                        .b(SdkBytes.fromByteArray(bytes))
                        .build();
            default:
                throw new CodecException("unsupported kind " + kind + " for " + name);
        }
    }

    Object decode(AttributeMapping mapping, AttributeKind kind, AttributeValue av) {
        String name = mapping.javaName();
        switch (kind) {
            case BOOLEAN:
                require(av.bool() != null, name, "BOOL", av);
                return av.bool();
            case BYTE:
                return parseWhole(name, av, "byte", Byte::valueOf);
            case SHORT:
                return parseWhole(name, av, "short", Short::valueOf);
            case INT:
                return parseWhole(name, av, "int", Integer::valueOf);
            case LONG:
                return parseWhole(name, av, "long", Long::valueOf);
            case FLOAT:
                return Float.valueOf(Ieee754.decodeFloat(av, name));
            case DOUBLE:
                return Double.valueOf(Ieee754.decodeDouble(av, name));
            case CHAR:
                return Character.valueOf(decodeChar(name, av));
            case STRING:
                require(av.s() != null, name, "S", av);
                return av.s();
            case SQL_DATE:
                require(av.s() != null, name, "S", av);
                return DateTimeWire.decodeSqlDate(av.s());
            case SQL_TIMESTAMP:
                return decodeTimestamp(name, av);
            case SQL_TIME:
                require(av.s() != null, name, "S", av);
                return DateTimeWire.decodeSqlTime(av.s());
            case RELADOMO_TIME:
                require(av.s() != null, name, "S", av);
                return DateTimeWire.decodeReladomoTime(av.s());
            case BIG_DECIMAL:
                require(av.s() != null, name, "S", av);
                try {
                    return new BigDecimal(av.s());
                } catch (NumberFormatException ex) {
                    throw new CodecException(
                            "attribute " + name + " is not a decimal string: " + av.s(), ex);
                }
            case BYTE_ARRAY:
                require(av.b() != null, name, "B", av);
                return av.b().asByteArray();
            default:
                throw new CodecException("unsupported kind " + kind + " for " + name);
        }
    }

    private AttributeValue encodeTimestamp(String name, Timestamp ts) {
        if (ts.getNanos() % 1_000_000 != 0) {
            throw new CodecException(
                    "attribute " + name
                            + " has sub-millisecond nanos; TemporalEncoder stores millisecond precision");
        }
        String wire;
        try {
            wire = TemporalEncoder.encode(ts);
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new CodecException(
                    "attribute " + name + " cannot be encoded by TemporalEncoder: " + ts.getTime(),
                    ex);
        }
        if (wire.length() != TemporalEncoder.WIDTH) {
            throw new CodecException(
                    "attribute " + name
                            + " TemporalEncoder produced width " + wire.length()
                            + " (expected " + TemporalEncoder.WIDTH
                            + "); year is outside the 4-digit UTC range");
        }
        return AttributeValue.builder().s(wire).build();
    }

    private Timestamp decodeTimestamp(String name, AttributeValue av) {
        require(av.s() != null, name, "S", av);
        Timestamp decoded;
        try {
            decoded = TemporalEncoder.decode(av.s());
        } catch (IllegalArgumentException ex) {
            throw new CodecException(
                    "attribute " + name + " is not a TemporalEncoder timestamp: " + av.s(), ex);
        }
        if (infinityOrNull != null
                && decoded.getTime() == infinityOrNull.getTime()
                && decoded.getNanos() == infinityOrNull.getNanos()) {
            return (Timestamp) infinityOrNull.clone();
        }
        return decoded;
    }

    private static AttributeValue encodeBigDecimal(String name, BigDecimal value) {
        DynamoDecimals.validate(value, name);
        return AttributeValue.builder().s(value.toPlainString()).build();
    }

    private static AttributeValue encodeChar(String name, char c) {
        if (Character.isSurrogate(c)) {
            return n(Integer.toString((int) c));
        }
        if (c == '\0') {
            // A one-char string is valid; empty string is not a char.
            return AttributeValue.builder().s(String.valueOf(c)).build();
        }
        return AttributeValue.builder().s(String.valueOf(c)).build();
    }

    private static char decodeChar(String name, AttributeValue av) {
        if (av.s() != null) {
            if (av.s().length() != 1) {
                throw new CodecException(
                        "attribute " + name + " char expected a single UTF-16 unit, got "
                                + av.s().length() + " characters");
            }
            return av.s().charAt(0);
        }
        if (av.n() != null) {
            int code;
            try {
                code = Integer.parseInt(av.n());
            } catch (NumberFormatException ex) {
                throw new CodecException(
                        "attribute " + name + " char N is not an integer: " + av.n(), ex);
            }
            if (code < 0 || code > 0xFFFF) {
                throw new CodecException(
                        "attribute " + name + " char code unit out of range: " + code);
            }
            return (char) code;
        }
        throw new CodecException("attribute " + name + " char expected S or N, got " + av.type());
    }

    private static <T> T parseWhole(String name, AttributeValue av, String type,
                                    java.util.function.Function<String, T> parser) {
        require(av.n() != null, name, "N", av);
        try {
            return parser.apply(av.n());
        } catch (NumberFormatException ex) {
            throw new CodecException(
                    "attribute " + name + " is not a valid " + type + ": " + av.n(), ex);
        }
    }

    private static AttributeValue n(String decimal) {
        return AttributeValue.builder().n(decimal).build();
    }

    private static <T> T expect(String name, Object value, Class<T> type) {
        if (!type.isInstance(value)) {
            throw new CodecException(
                    "attribute " + name + " expected " + type.getName() + ", got "
                            + (value == null ? "null" : value.getClass().getName()));
        }
        return type.cast(value);
    }

    private static void require(boolean ok, String name, String expected, AttributeValue av) {
        if (!ok) {
            throw new CodecException(
                    "attribute " + name + " expected " + expected + ", got " + av.type());
        }
    }
}
