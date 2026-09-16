package io.reladynamo.ddb.codec;

import io.reladynamo.core.temporal.TemporalEncoder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.TimeZone;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import static io.reladynamo.ddb.codec.CodecFixtures.INFINITY;
import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;

final class TimestampCodecProperties {

    private final ItemCodec codec =
            new ItemCodec(entity(attr("businessDateFrom", "java.sql.Timestamp", false)));

    @Provide
    Arbitrary<Timestamp> timestampsInIsoRange() {
        long min = Instant.parse("0001-01-01T00:00:00Z").toEpochMilli();
        long max = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();
        return Arbitraries.longs().between(min, max).map(TimestampCodecProperties::millisOnly);
    }

    @Property(tries = 2000)
    void should_round_trip_millis_when_timestamp_in_encoder_range(
            @ForAll("timestampsInIsoRange") Timestamp original) {
        Map<String, AttributeValue> item = codec.encode(values("businessDateFrom", original));
        Timestamp decoded = (Timestamp) codec.decode(item).get("businessDateFrom");
        assertThat(decoded.getTime()).isEqualTo(original.getTime());
        assertThat(decoded.getNanos()).isEqualTo(original.getNanos());
        assertThat(item.get("businessDateFrom").s()).isEqualTo(TemporalEncoder.encode(original));
        assertThat(item.get("businessDateFrom").s()).hasSize(TemporalEncoder.WIDTH);
    }

    @Property(tries = 1000)
    void should_preserve_chronological_lexicographic_order(
            @ForAll("timestampsInIsoRange") Timestamp a,
            @ForAll("timestampsInIsoRange") Timestamp b) {
        String wa = codec.encode(values("businessDateFrom", a)).get("businessDateFrom").s();
        String wb = codec.encode(values("businessDateFrom", b)).get("businessDateFrom").s();
        int lex = wa.compareTo(wb);
        int chron = Long.compare(a.getTime(), b.getTime());
        assertThat(Integer.signum(lex)).isEqualTo(Integer.signum(chron));
    }

    @Property(tries = 500)
    void should_sort_pre_epoch_before_post_epoch(
            @ForAll @LongRange(min = -86_400_000L * 365 * 50, max = -1L) long pre,
            @ForAll @LongRange(min = 0L, max = 86_400_000L * 365 * 50) long post) {
        Timestamp a = millisOnly(pre);
        Timestamp b = millisOnly(post);
        String wa = codec.encode(values("businessDateFrom", a)).get("businessDateFrom").s();
        String wb = codec.encode(values("businessDateFrom", b)).get("businessDateFrom").s();
        assertThat(wa.compareTo(wb)).isNegative();
    }

    @Test
    void should_round_trip_infinity_to_configured_sentinel() {
        Map<String, AttributeValue> item = codec.encode(values("businessDateFrom", INFINITY));
        Timestamp decoded = (Timestamp) codec.decode(item).get("businessDateFrom");
        assertThat(decoded.getTime()).isEqualTo(INFINITY.getTime());
        assertThat(decoded.getNanos()).isEqualTo(INFINITY.getNanos());
        assertThat(item.get("businessDateFrom").s()).isEqualTo(TemporalEncoder.encode(INFINITY));
    }

    @Test
    void should_sort_infinity_after_ordinary_business_date() {
        Timestamp business = Timestamp.from(Instant.parse("2026-09-12T10:00:00Z"));
        String inf = codec.encode(values("businessDateFrom", INFINITY)).get("businessDateFrom").s();
        String biz = codec.encode(values("businessDateFrom", business)).get("businessDateFrom").s();
        assertThat(inf.compareTo(biz)).isPositive();
    }

    @Test
    void should_encode_independent_of_jvm_default_timezone() {
        Timestamp ts = Timestamp.from(Instant.parse("2026-09-12T10:00:00.123Z"));
        String expected = TemporalEncoder.encode(ts);
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            String west = codec.encode(values("businessDateFrom", ts)).get("businessDateFrom").s();
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            String east = codec.encode(values("businessDateFrom", ts)).get("businessDateFrom").s();
            assertThat(west).isEqualTo(expected);
            assertThat(east).isEqualTo(expected);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private static Timestamp millisOnly(long millis) {
        Timestamp ts = new Timestamp(millis);
        int millisPart = (int) (Math.floorMod(millis, 1000L) * 1_000_000L);
        ts.setNanos(millisPart);
        return ts;
    }
}
