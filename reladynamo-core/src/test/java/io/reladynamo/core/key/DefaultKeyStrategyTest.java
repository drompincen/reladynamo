package io.reladynamo.core.key;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.mapping.MappingValidator;
import io.reladynamo.core.mapping.ReladynamoConfigException;
import io.reladynamo.core.temporal.TemporalEncoder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DefaultKeyStrategyTest {

    private final KeyStrategy keys = new DefaultKeyStrategy();

    @Test
    void should_build_partition_key_when_single_long_pk() {
        EntityMapping mapping = entity(
                "com.acme.domain.Customer",
                TemporalMapping.none(),
                pk("customerId", "long"));

        String pk = keys.partitionKey(mapping, Collections.singletonMap("customerId", 42L));

        assertThat(pk).isEqualTo("v1#CUSTOMER#42");
    }

    @Test
    void should_join_pk_components_in_xml_order() {
        EntityMapping mapping = entity(
                "com.acme.domain.Position",
                TemporalMapping.none(),
                pk("accountId", "long"),
                pk("productId", "int"));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("productId", 7);
        values.put("accountId", 42L);

        String pk = keys.partitionKey(mapping, values);

        assertThat(pk).isEqualTo("v1#POSITION#42#7");
    }

    @Test
    void should_omit_sort_key_when_flavour_is_none() {
        EntityMapping mapping = entity(
                "com.acme.domain.Car",
                TemporalMapping.none(),
                pk("carId", "int"));

        String sk = keys.sortKey(mapping, utc(2026, Calendar.JUNE, 1, 0, 0, 0, 0),
                utc(2020, Calendar.JANUARY, 1, 0, 0, 0, 0));

        assertThat(sk).isEqualTo(DefaultKeyStrategy.NON_DATED_SORT_KEY);
    }

    @Test
    void should_place_processing_before_business_in_sort_key() {
        Timestamp processing = utc(2026, Calendar.AUGUST, 11, 14, 32, 9, 123);
        Timestamp business = utc(2020, Calendar.JANUARY, 1, 0, 0, 0, 0);
        EntityMapping mapping = entity(
                "com.acme.domain.Customer",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, utc(9999, Calendar.DECEMBER, 1, 23, 59, 0, 0)),
                pk("customerId", "long"));

        String sk = keys.sortKey(mapping, processing, business);

        assertThat(sk).isEqualTo(
                "v1#P#" + TemporalEncoder.encode(processing)
                        + "#B#" + TemporalEncoder.encode(business));
        assertThat(sk.indexOf("#P#")).isLessThan(sk.indexOf("#B#"));
        assertThat(sk).doesNotContain("T").doesNotContain("Z");
    }

    @Test
    void should_use_only_business_axis_when_business_only() {
        Timestamp business = utc(2020, Calendar.JANUARY, 1, 0, 0, 0, 0);
        EntityMapping mapping = entity(
                "com.acme.domain.Pet",
                TemporalMapping.of(TemporalMapping.Flavour.BUSINESS_ONLY, utc(9999, Calendar.DECEMBER, 1, 23, 59, 0, 0)),
                pk("petId", "long"));

        String sk = keys.sortKey(mapping, null, business);

        assertThat(sk).isEqualTo("v1#B#" + TemporalEncoder.encode(business));
        assertThat(sk).doesNotContain("#P#");
    }

    @Test
    void should_use_only_processing_axis_when_audit_only() {
        Timestamp processing = utc(2026, Calendar.AUGUST, 11, 14, 32, 9, 123);
        EntityMapping mapping = entity(
                "com.acme.domain.ClassificationResult",
                TemporalMapping.of(TemporalMapping.Flavour.AUDIT_ONLY, utc(9999, Calendar.DECEMBER, 1, 23, 59, 0, 0)),
                pk("resultId", "int"));

        String sk = keys.sortKey(mapping, processing, null);

        assertThat(sk).isEqualTo("v1#P#" + TemporalEncoder.encode(processing));
        assertThat(sk).doesNotContain("#B#");
    }

    @Test
    void should_reject_partition_key_exceeding_2048_bytes() {
        EntityMapping mapping = entity(
                "com.acme.domain.Customer",
                TemporalMapping.none(),
                pk("code", "String"));
        String tooLong = repeat('x', 2048);

        assertThatThrownBy(() -> keys.partitionKey(mapping, Collections.singletonMap("code", tooLong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.acme.domain.Customer")
                .hasMessageContaining("2048")
                .hasMessageContaining(tooLong.substring(0, 16));
    }

    @Test
    void should_reject_sort_key_exceeding_1024_bytes() {
        String tooLong = "v1#P#" + repeat('9', 1024);

        assertThatThrownBy(() -> DefaultKeyStrategy.checkLimit(
                "com.acme.domain.Customer", "sort key", tooLong, DefaultKeyStrategy.MAX_SORT_KEY_BYTES))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.acme.domain.Customer")
                .hasMessageContaining("1024")
                .hasMessageContaining(tooLong.substring(0, 16));
        assertThat(DefaultKeyStrategy.MAX_SORT_KEY_BYTES).isEqualTo(1024);
    }

    @Test
    void should_reject_hash_in_partition_key_component() {
        EntityMapping mapping = entity(
                "com.acme.domain.Customer",
                TemporalMapping.none(),
                pk("code", "String"));

        assertThatThrownBy(() -> keys.partitionKey(mapping, Collections.singletonMap("code", "A#B")))
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessage(MappingValidator.hashInKeyAttribute("com.acme.domain.Customer", "code").getMessage());
    }

    @Test
    void should_encode_boolean_pk_as_t_or_f() {
        EntityMapping mapping = entity(
                "com.acme.domain.Flag",
                TemporalMapping.none(),
                pk("on", "boolean"));

        assertThat(keys.partitionKey(mapping, Collections.singletonMap("on", Boolean.TRUE)))
                .isEqualTo("v1#FLAG#T");
        assertThat(keys.partitionKey(mapping, Collections.singletonMap("on", Boolean.FALSE)))
                .isEqualTo("v1#FLAG#F");
    }

    @Test
    void should_reject_null_processing_from_when_bitemporal() {
        EntityMapping mapping = entity(
                "com.acme.domain.Customer",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, utc(9999, Calendar.DECEMBER, 1, 23, 59, 0, 0)),
                pk("customerId", "long"));

        assertThatThrownBy(() -> keys.sortKey(mapping, null, utc(2020, Calendar.JANUARY, 1, 0, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.acme.domain.Customer")
                .hasMessageContaining("processingDateFrom");
    }

    @Test
    void should_name_entity_when_pk_value_is_missing() {
        EntityMapping mapping = entity(
                "com.acme.domain.Customer",
                TemporalMapping.none(),
                pk("customerId", "long"));

        assertThatThrownBy(() -> keys.partitionKey(mapping, Collections.emptyMap()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.acme.domain.Customer")
                .hasMessageContaining("customerId");
    }

    @Test
    void should_emit_identical_keys_for_independently_constructed_byte_arrays() {
        EntityMapping mapping = entity(
                "com.acme.domain.BlobKey",
                TemporalMapping.none(),
                pk("id", "byte[]"));
        byte[] first = new byte[] {0x0a, 0x1b, (byte) 0xff, 0x00};
        byte[] second = new byte[] {0x0a, 0x1b, (byte) 0xff, 0x00};

        String key1 = keys.partitionKey(mapping, Collections.singletonMap("id", first));
        String key2 = keys.partitionKey(mapping, Collections.singletonMap("id", second));

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isEqualTo("v1#BLOBKEY#0a1bff00");
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void should_emit_identical_keys_for_independently_constructed_big_decimals() {
        EntityMapping mapping = entity(
                "com.acme.domain.PriceKey",
                TemporalMapping.none(),
                pk("id", "BigDecimal"));
        BigDecimal first = new BigDecimal("1E+2");
        BigDecimal second = new BigDecimal("1E+2");

        String key1 = keys.partitionKey(mapping, Collections.singletonMap("id", first));
        String key2 = keys.partitionKey(mapping, Collections.singletonMap("id", second));

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isEqualTo("v1#PRICEKEY#100");
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void should_emit_identical_utc_date_keys_across_default_timezones() {
        EntityMapping mapping = entity(
                "com.acme.domain.DayKey",
                TemporalMapping.none(),
                pk("id", "Date"));
        long millis = Instant.parse("2020-06-15T00:00:00Z").toEpochMilli();
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            String west = keys.partitionKey(mapping,
                    Collections.singletonMap("id", new Date(millis)));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            String utc = keys.partitionKey(mapping,
                    Collections.singletonMap("id", new Date(millis)));

            assertThat(west).isEqualTo(utc);
            assertThat(west).isEqualTo("v1#DAYKEY#2020-06-15");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void should_emit_identical_keys_for_independently_constructed_sql_times() {
        EntityMapping mapping = entity(
                "com.acme.domain.ClockKey",
                TemporalMapping.none(),
                pk("id", "java.sql.Time"));
        long millis = Instant.parse("1970-01-01T14:05:09.123Z").toEpochMilli();
        Time first = new Time(millis);
        Time second = new Time(millis);

        String key1 = keys.partitionKey(mapping, Collections.singletonMap("id", first));
        String key2 = keys.partitionKey(mapping, Collections.singletonMap("id", second));

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isEqualTo("v1#CLOCKKEY#14:05:09.123");
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void should_emit_identical_keys_for_independently_constructed_reladomo_times() {
        EntityMapping mapping = entity(
                "com.acme.domain.ShiftKey",
                TemporalMapping.none(),
                pk("id", "Time"));
        com.gs.fw.common.mithra.util.Time first =
                com.gs.fw.common.mithra.util.Time.withMillis(9, 30, 0, 7);
        com.gs.fw.common.mithra.util.Time second =
                com.gs.fw.common.mithra.util.Time.withMillis(9, 30, 0, 7);

        String key1 = keys.partitionKey(mapping, Collections.singletonMap("id", first));
        String key2 = keys.partitionKey(mapping, Collections.singletonMap("id", second));

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isEqualTo("v1#SHIFTKEY#09:30:00.007");
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void should_emit_identical_keys_for_independently_constructed_strings_longs_and_timestamps() {
        EntityMapping strings = entity(
                "com.acme.domain.NameKey", TemporalMapping.none(), pk("id", "String"));
        assertThat(keys.partitionKey(strings, Collections.singletonMap("id", new String("alpha"))))
                .isEqualTo(keys.partitionKey(strings, Collections.singletonMap("id", new String("alpha"))))
                .isEqualTo("v1#NAMEKEY#alpha");

        EntityMapping longs = entity(
                "com.acme.domain.LongKey", TemporalMapping.none(), pk("id", "long"));
        assertThat(keys.partitionKey(longs, Collections.singletonMap("id", Long.valueOf(42L))))
                .isEqualTo(keys.partitionKey(longs, Collections.singletonMap("id", Long.valueOf(42L))))
                .isEqualTo("v1#LONGKEY#42");

        Timestamp ts1 = utc(2026, Calendar.JANUARY, 2, 3, 4, 5, 6);
        Timestamp ts2 = utc(2026, Calendar.JANUARY, 2, 3, 4, 5, 6);
        EntityMapping stamps = entity(
                "com.acme.domain.TsKey", TemporalMapping.none(), pk("id", "Timestamp"));
        assertThat(keys.partitionKey(stamps, Collections.singletonMap("id", ts1)))
                .isEqualTo(keys.partitionKey(stamps, Collections.singletonMap("id", ts2)))
                .isEqualTo("v1#TSKEY#" + TemporalEncoder.encode(ts1));
        assertThat(ts1).isNotSameAs(ts2);
    }

    @Test
    void should_emit_identical_keys_for_independently_constructed_integrals_and_char() {
        EntityMapping ints = entity(
                "com.acme.domain.IntKey", TemporalMapping.none(), pk("id", "int"));
        assertThat(keys.partitionKey(ints, Collections.singletonMap("id", Integer.valueOf(9))))
                .isEqualTo(keys.partitionKey(ints, Collections.singletonMap("id", Integer.valueOf(9))))
                .isEqualTo("v1#INTKEY#9");

        EntityMapping shorts = entity(
                "com.acme.domain.ShortKey", TemporalMapping.none(), pk("id", "short"));
        assertThat(keys.partitionKey(shorts, Collections.singletonMap("id", Short.valueOf((short) 4))))
                .isEqualTo(keys.partitionKey(shorts, Collections.singletonMap("id", Short.valueOf((short) 4))))
                .isEqualTo("v1#SHORTKEY#4");

        EntityMapping bytes = entity(
                "com.acme.domain.ByteKey", TemporalMapping.none(), pk("id", "byte"));
        assertThat(keys.partitionKey(bytes, Collections.singletonMap("id", Byte.valueOf((byte) 3))))
                .isEqualTo(keys.partitionKey(bytes, Collections.singletonMap("id", Byte.valueOf((byte) 3))))
                .isEqualTo("v1#BYTEKEY#3");

        EntityMapping chars = entity(
                "com.acme.domain.CharKey", TemporalMapping.none(), pk("id", "char"));
        assertThat(keys.partitionKey(chars, Collections.singletonMap("id", Character.valueOf('Z'))))
                .isEqualTo(keys.partitionKey(chars, Collections.singletonMap("id", Character.valueOf('Z'))))
                .isEqualTo("v1#CHARKEY#Z");
    }

    @Test
    void should_refuse_double_and_float_key_components() {
        EntityMapping doubles = entity(
                "com.acme.domain.FloatKey", TemporalMapping.none(), pk("id", "double"));
        assertThatThrownBy(() -> keys.partitionKey(doubles, Collections.singletonMap("id", Double.valueOf(1.5d))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("com.acme.domain.FloatKey")
                .hasMessageContaining("id")
                .hasMessageContaining("Double");

        EntityMapping floats = entity(
                "com.acme.domain.FloatKey", TemporalMapping.none(), pk("id", "float"));
        assertThatThrownBy(() -> keys.partitionKey(floats, Collections.singletonMap("id", Float.valueOf(1.5f))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Float");
    }


    private static EntityMapping entity(String className, TemporalMapping temporal, AttributeMapping... attributes) {
        return new EntityMapping(className, className.substring(className.lastIndexOf('.') + 1).toUpperCase(),
                temporal, Arrays.asList(attributes));
    }

    private static AttributeMapping pk(String name, String javaType) {
        return new AttributeMapping(name, name.toUpperCase(), javaType, true, false);
    }

    private static Timestamp utc(int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(y, mo, d, h, mi, s);
        Timestamp timestamp = new Timestamp(calendar.getTimeInMillis());
        timestamp.setNanos(ms * 1_000_000);
        return timestamp;
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
