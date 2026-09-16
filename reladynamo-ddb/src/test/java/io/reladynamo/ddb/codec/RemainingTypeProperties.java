package io.reladynamo.ddb.codec;

import java.sql.Date;
import java.sql.Time;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

import static io.reladynamo.ddb.codec.CodecFixtures.attr;
import static io.reladynamo.ddb.codec.CodecFixtures.entity;
import static io.reladynamo.ddb.codec.CodecFixtures.values;
import static org.assertj.core.api.Assertions.assertThat;

final class RemainingTypeProperties {

    private final ItemCodec strings = new ItemCodec(entity(attr("v", "String", false)));
    private final ItemCodec bools = new ItemCodec(entity(attr("v", "boolean", false)));
    private final ItemCodec chars = new ItemCodec(entity(attr("v", "char", false)));
    private final ItemCodec dates = new ItemCodec(entity(attr("v", "java.sql.Date", false)));
    private final ItemCodec times = new ItemCodec(entity(attr("v", "java.sql.Time", false)));
    private final ItemCodec blobs = new ItemCodec(entity(attr("v", "byte[]", false)));

    @Property(tries = 500)
    void should_round_trip_every_string_including_empty(
            @ForAll @StringLength(max = 64) String original) {
        Map<String, Object> decoded = strings.decode(strings.encode(values("v", original)));
        assertThat(decoded.get("v")).isEqualTo(original);
        assertThat(strings.encode(values("v", original)).get("v").s()).isEqualTo(original);
    }

    @Property(tries = 32)
    void should_round_trip_every_boolean(@ForAll boolean original) {
        Map<String, Object> decoded = bools.decode(bools.encode(values("v", original)));
        assertThat(decoded.get("v")).isEqualTo(original);
        assertThat(bools.encode(values("v", original)).get("v").bool()).isEqualTo(original);
    }

    @Property(tries = 500)
    void should_round_trip_every_char(@ForAll char original) {
        Map<String, Object> decoded = chars.decode(chars.encode(values("v", original)));
        assertThat(decoded.get("v")).isEqualTo(original);
    }

    @Property(tries = 500)
    void should_round_trip_utc_sql_dates(@ForAll("utcDates") Date original) {
        Map<String, Object> decoded = dates.decode(dates.encode(values("v", original)));
        Date got = (Date) decoded.get("v");
        assertThat(got.getTime()).isEqualTo(original.getTime());
        assertThat(dates.encode(values("v", original)).get("v").s()).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Property(tries = 500)
    void should_round_trip_sql_times(@ForAll("sqlTimes") Time original) {
        Map<String, Object> decoded = times.decode(times.encode(values("v", original)));
        Time got = (Time) decoded.get("v");
        assertThat(got.getTime()).isEqualTo(original.getTime());
    }

    @Property(tries = 200)
    void should_round_trip_byte_arrays(@ForAll @Size(max = 64) byte[] original) {
        Map<String, Object> decoded = blobs.decode(blobs.encode(values("v", original)));
        assertThat((byte[]) decoded.get("v")).isEqualTo(original);
    }

    @Provide
    Arbitrary<Date> utcDates() {
        return Arbitraries.longs().between(0, 365L * 200).map(days -> {
            LocalDate localDate = LocalDate.of(1970, 1, 1).plusDays(days);
            return new Date(localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        });
    }

    @Provide
    Arbitrary<Time> sqlTimes() {
        return Arbitraries.longs().between(0, 86_399_999L).map(Time::new);
    }
}
