package io.reladynamo.ddb.codec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.temporal.TemporalEncoder;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static io.reladynamo.ddb.codec.CodecFixtures.INFINITY;
import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.pk;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ItemCodecRoundTripTest {

    @Test
    void should_round_trip_boolean_true_and_false() {
        ItemCodec codec = new ItemCodec(entity(attr("flag", "boolean", false)));
        assertRoundTrip(codec, "flag", Boolean.TRUE);
        assertRoundTrip(codec, "flag", Boolean.FALSE);
        assertThat(codec.encode(values("flag", Boolean.TRUE)).get("flag").bool()).isTrue();
    }

    @Test
    void should_round_trip_byte_boundaries() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "byte", false)));
        assertRoundTrip(codec, "v", Byte.MIN_VALUE);
        assertRoundTrip(codec, "v", Byte.MAX_VALUE);
        assertRoundTrip(codec, "v", (byte) 0);
        assertRoundTrip(codec, "v", (byte) -1);
        assertThat(codec.encode(values("v", Byte.MIN_VALUE)).get("v").n()).isEqualTo("-128");
    }

    @Test
    void should_round_trip_short_boundaries() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "short", false)));
        assertRoundTrip(codec, "v", Short.MIN_VALUE);
        assertRoundTrip(codec, "v", Short.MAX_VALUE);
    }

    @Test
    void should_round_trip_int_boundaries() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "int", false)));
        assertRoundTrip(codec, "v", Integer.MIN_VALUE);
        assertRoundTrip(codec, "v", Integer.MAX_VALUE);
        assertRoundTrip(codec, "v", 0);
        assertThat(codec.encode(values("v", Integer.MIN_VALUE)).get("v").n())
                .isEqualTo(Integer.toString(Integer.MIN_VALUE));
    }

    @Test
    void should_round_trip_long_boundaries() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "long", false)));
        assertRoundTrip(codec, "v", Long.MIN_VALUE);
        assertRoundTrip(codec, "v", Long.MAX_VALUE);
        assertThat(codec.encode(values("v", Long.MIN_VALUE)).get("v").n())
                .isEqualTo(Long.toString(Long.MIN_VALUE));
    }

    @Test
    void should_round_trip_char_bmp_and_nul() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "char", false)));
        assertRoundTrip(codec, "v", 'A');
        assertRoundTrip(codec, "v", '€');
        assertRoundTrip(codec, "v", '\0');
        assertThat(codec.encode(values("v", 'A')).get("v").s()).isEqualTo("A");
    }

    @Test
    void should_round_trip_unpaired_surrogate_char_as_numeric_code_unit() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "char", false)));
        char surrogate = '\uD800';
        Map<String, AttributeValue> item = codec.encode(values("v", surrogate));
        assertThat(item.get("v").n()).isEqualTo(Integer.toString((int) surrogate));
        assertRoundTrip(codec, "v", surrogate);
    }

    @Test
    void should_round_trip_string_including_empty() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "String", false)));
        assertRoundTrip(codec, "v", "hello");
        assertRoundTrip(codec, "v", "");
        assertThat(codec.encode(values("v", "")).get("v").s()).isEqualTo("");
    }

    @Test
    void should_round_trip_sql_date_as_utc_civil_date() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "java.sql.Date", false)));
        Date date = utcDate(2026, 9, 13);
        Map<String, AttributeValue> item = codec.encode(values("v", date));
        assertThat(item.get("v").s()).isEqualTo("2026-09-13");
        Date decoded = (Date) codec.decode(item).get("v");
        assertThat(decoded.getTime()).isEqualTo(date.getTime());
    }

    @Test
    void should_round_trip_sql_time_as_utc_time_of_day() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "java.sql.Time", false)));
        Time time = new Time(14 * 3600_000L + 30 * 60_000L + 123L);
        Map<String, AttributeValue> item = codec.encode(values("v", time));
        assertThat(item.get("v").s()).isEqualTo("14:30:00.123");
        Time decoded = (Time) codec.decode(item).get("v");
        assertThat(decoded.getTime()).isEqualTo(time.getTime());
    }

    @Test
    void should_round_trip_timestamp_through_temporal_encoder() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "java.sql.Timestamp", false)));
        Timestamp ts = Timestamp.from(Instant.parse("2026-09-12T13:45:01.123Z"));
        Map<String, AttributeValue> item = codec.encode(values("v", ts));
        assertThat(item.get("v").s()).isEqualTo(TemporalEncoder.encode(ts));
        assertThat(item.get("v").s()).hasSize(TemporalEncoder.WIDTH);
        Timestamp decoded = (Timestamp) codec.decode(item).get("v");
        assertThat(decoded.getTime()).isEqualTo(ts.getTime());
        assertThat(decoded.getNanos()).isEqualTo(ts.getNanos());
    }

    @Test
    void should_round_trip_infinity_sentinel_to_configured_value() {
        EntityMapping mapping = entity(attr("v", "java.sql.Timestamp", false));
        ItemCodec codec = new ItemCodec(mapping);
        Map<String, AttributeValue> item = codec.encode(values("v", INFINITY));
        Timestamp decoded = (Timestamp) codec.decode(item).get("v");
        assertThat(decoded.getTime()).isEqualTo(INFINITY.getTime());
        assertThat(decoded.getNanos()).isEqualTo(INFINITY.getNanos());
    }

    @Test
    void should_round_trip_pre_epoch_timestamp() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "java.sql.Timestamp", false)));
        Timestamp ts = new Timestamp(-1500L);
        int millisPart = (int) (Math.floorMod(-1500L, 1000L) * 1_000_000L);
        ts.setNanos(millisPart);
        Map<String, AttributeValue> item = codec.encode(values("v", ts));
        Timestamp decoded = (Timestamp) codec.decode(item).get("v");
        assertThat(decoded.getTime()).isEqualTo(ts.getTime());
        assertThat(decoded.getNanos()).isEqualTo(ts.getNanos());
    }

    @Test
    void should_round_trip_byte_array_including_empty() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "byte[]", false)));
        assertRoundTrip(codec, "v", new byte[] {0, 1, (byte) 255});
        assertRoundTrip(codec, "v", new byte[0]);
    }

    @Test
    void should_use_item_name_on_wire_not_java_name() {
        List<AttributeMapping> attributes = new ArrayList<>();
        attributes.add(pk("id", "long"));
        attributes.add(attr("quantity", "QTY", "int", false, false));
        ItemCodec codec = new ItemCodec(entity("Position", attributes));
        Map<String, Object> original = values("quantity", 7);

        Map<String, AttributeValue> item = codec.encode(original);

        assertThat(item).containsKey("QTY");
        assertThat(item).doesNotContainKey("quantity");
        assertThat(item.get("QTY").n()).isEqualTo("7");
        assertThat(codec.decode(item).get("quantity")).isEqualTo(7);
    }

    @Test
    void should_reject_sub_millisecond_timestamp() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "java.sql.Timestamp", false)));
        Timestamp ts = Timestamp.from(Instant.parse("2026-09-12T13:45:01.123Z"));
        ts.setNanos(123_456_789);

        assertThatThrownBy(() -> codec.encode(values("v", ts)))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("sub-millisecond");
    }

    @Test
    void should_encode_date_independent_of_jvm_default_timezone() {
        ItemCodec codec = new ItemCodec(entity(attr("v", "java.sql.Date", false)));
        Date date = utcDate(2026, 6, 1);
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
            String west = codec.encode(values("v", date)).get("v").s();
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            String east = codec.encode(values("v", date)).get("v").s();
            assertThat(west).isEqualTo("2026-06-01");
            assertThat(east).isEqualTo("2026-06-01");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    static void assertRoundTrip(ItemCodec codec, String name, Object value) {
        Map<String, Object> original = values(name, value);
        Map<String, AttributeValue> item = codec.encode(original);
        Map<String, Object> decoded = codec.decode(item);
        Object got = decoded.get(name);
        if (value instanceof byte[]) {
            assertThat((byte[]) got).isEqualTo(value);
        } else if (value instanceof Double) {
            assertThat(Double.doubleToRawLongBits((Double) got))
                    .isEqualTo(Double.doubleToRawLongBits((Double) value));
        } else if (value instanceof Float) {
            assertThat(Float.floatToRawIntBits((Float) got))
                    .isEqualTo(Float.floatToRawIntBits((Float) value));
        } else {
            assertThat(got).isEqualTo(value);
        }
        assertThat(decoded.get("id")).isEqualTo(1L);
        assertThat(item).containsKey(ItemCodec.SCHEMA_VERSION_ATTR);
    }

    static Date utcDate(int year, int month, int day) {
        LocalDate localDate = LocalDate.of(year, month, day);
        return new Date(localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
    }
}
