package io.reladynamo.core.key;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.mapping.MappingValidator;
import io.reladynamo.core.temporal.TemporalEncoder;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * Canonical partition-key component encoding. Reconstruction is stable across JVMs and
 * across independently constructed values with equal content (notably {@code byte[]}).
 *
 * <p>Pinned types and wire forms:
 * <ul>
 *   <li>{@link Timestamp} — {@link TemporalEncoder} (fixed-width UTC millis)</li>
 *   <li>{@link Boolean} — {@code T} / {@code F}</li>
 *   <li>integral wrappers — signed decimal {@code toString}</li>
 *   <li>{@link Character} / {@link String} — UTF-16 / UTF-8 text; {@code #} is CFG-008</li>
 *   <li>{@link Date} — UTC civil date {@code yyyy-MM-dd}</li>
 *   <li>{@link Time} — UTC time-of-day {@code HH:mm:ss.SSS}</li>
 *   <li>Reladomo {@code Time} — {@code HH:mm:ss.SSS} from hour/minute/second/milli</li>
 *   <li>{@link BigDecimal} — {@link BigDecimal#toPlainString()}</li>
 *   <li>{@code byte[]} — lowercase hex of the content, not identity hash</li>
 * </ul>
 * {@link Float}, {@link Double}, and any other type are refused.
 */
public final class KeyComponentEncoder {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter TIME_OF_DAY_UTC =
            new DateTimeFormatterBuilder()
                    .appendPattern("HH:mm:ss")
                    .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
                    .toFormatter()
                    .withZone(ZoneOffset.UTC);

    private KeyComponentEncoder() {
    }

    public static String encode(Object value) {
        return encode(null, null, value);
    }

    public static String encode(EntityMapping mapping, AttributeMapping attribute, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("key component value is required"
                    + where(mapping, attribute));
        }
        String encoded = encodeValue(mapping, attribute, value);
        if (encoded.indexOf('#') >= 0) {
            if (mapping != null && attribute != null) {
                throw MappingValidator.hashInKeyAttribute(mapping.className(), attribute.javaName());
            }
            throw new IllegalArgumentException("key component contains '#': " + encoded);
        }
        return encoded;
    }

    private static String encodeValue(EntityMapping mapping, AttributeMapping attribute, Object value) {
        if (value instanceof Timestamp) {
            return TemporalEncoder.encode((Timestamp) value);
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue() ? "T" : "F";
        }
        if (value instanceof byte[]) {
            return hex((byte[]) value);
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof Date) {
            return ISO_DATE.format(Instant.ofEpochMilli(((Date) value).getTime()));
        }
        if (value instanceof Time) {
            return TIME_OF_DAY_UTC.format(Instant.ofEpochMilli(((Time) value).getTime()));
        }
        if (value instanceof com.gs.fw.common.mithra.util.Time) {
            com.gs.fw.common.mithra.util.Time time = (com.gs.fw.common.mithra.util.Time) value;
            return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
                    (int) time.getHour(),
                    (int) time.getMinute(),
                    (int) time.getSecond(),
                    time.getMillisecond());
        }
        if (value instanceof Character) {
            return String.valueOf(((Character) value).charValue());
        }
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        throw unsupported(mapping, attribute, value);
    }

    private static IllegalArgumentException unsupported(EntityMapping mapping,
                                                        AttributeMapping attribute,
                                                        Object value) {
        StringBuilder sb = new StringBuilder("unsupported primary-key type ");
        sb.append(value.getClass().getName());
        sb.append(where(mapping, attribute));
        sb.append("; Reladynamo partition-key components must be String, boolean, ");
        sb.append("byte/short/int/long, char, Timestamp, Date, Time, BigDecimal, or byte[]");
        return new IllegalArgumentException(sb.toString());
    }

    private static String where(EntityMapping mapping, AttributeMapping attribute) {
        if (mapping == null || attribute == null) {
            return "";
        }
        return " for " + mapping.className() + "." + attribute.javaName();
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
