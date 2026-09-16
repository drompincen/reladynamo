package io.reladynamo.core.temporal;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sort key carries the temporal axes, so an encoding bug here does not surface as a decode error
 * — it surfaces as a query that silently returns the wrong row. These are the properties the rest of
 * the adapter is allowed to assume.
 */
class TemporalEncoderTest {

    private static final Timestamp INFINITY = utc(9999, Calendar.DECEMBER, 1, 23, 59, 0, 0);

    @Test
    void round_trips_to_the_exact_millisecond() {
        Timestamp t = utc(2026, Calendar.JUNE, 1, 14, 32, 9, 123);
        assertThat(TemporalEncoder.decode(TemporalEncoder.encode(t))).isEqualTo(t);
    }

    @Test
    void round_trips_the_infinity_sentinel_to_the_same_value() {
        // Reladomo compares against its configured infinity by value. An encoding that returns a
        // near-miss breaks every current-row query while looking correct in isolation.
        assertThat(TemporalEncoder.decode(TemporalEncoder.encode(INFINITY))).isEqualTo(INFINITY);
    }

    @Test
    void infinity_sorts_after_every_legal_business_date() {
        String inf = TemporalEncoder.encode(INFINITY);
        for (Timestamp t : new Timestamp[]{
                utc(1970, Calendar.JANUARY, 1, 0, 0, 0, 0),
                utc(2026, Calendar.SEPTEMBER, 13, 23, 59, 59, 999),
                utc(9999, Calendar.NOVEMBER, 30, 23, 59, 59, 999)}) {
            assertThat(TemporalEncoder.encode(t)).isLessThan(inf);
        }
    }

    @Test
    void lexicographic_order_matches_chronological_order() {
        List<Timestamp> chronological = new ArrayList<>();
        chronological.add(utc(1969, Calendar.JULY, 20, 20, 17, 40, 0));   // pre-epoch
        chronological.add(utc(1970, Calendar.JANUARY, 1, 0, 0, 0, 0));    // epoch
        chronological.add(utc(1999, Calendar.DECEMBER, 31, 23, 59, 59, 999));
        chronological.add(utc(2000, Calendar.JANUARY, 1, 0, 0, 0, 0));
        chronological.add(utc(2026, Calendar.JUNE, 1, 0, 0, 0, 1));
        chronological.add(INFINITY);

        for (int i = 1; i < chronological.size(); i++) {
            String prev = TemporalEncoder.encode(chronological.get(i - 1));
            String cur = TemporalEncoder.encode(chronological.get(i));
            assertThat(prev)
                    .as("%s must sort before %s", chronological.get(i - 1), chronological.get(i))
                    .isLessThan(cur);
        }
    }

    @Test
    void every_encoding_is_the_same_width_because_ragged_keys_break_range_queries() {
        int width = TemporalEncoder.encode(INFINITY).length();
        assertThat(TemporalEncoder.encode(utc(1969, Calendar.JULY, 20, 20, 17, 40, 0)).length())
                .isEqualTo(width);
        assertThat(TemporalEncoder.encode(utc(2026, Calendar.JUNE, 1, 0, 0, 0, 1)).length())
                .isEqualTo(width);
    }

    @Test
    void encoding_is_utc_regardless_of_the_default_timezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            Timestamp t = utc(2026, Calendar.JUNE, 1, 14, 32, 9, 123);
            TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"));
            String denver = TemporalEncoder.encode(t);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            String tokyo = TemporalEncoder.encode(t);
            assertThat(denver).isEqualTo(tokyo);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void rejects_null_rather_than_encoding_a_placeholder() {
        assertThatThrownBy(() -> TemporalEncoder.encode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Timestamp utc(int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo, d, h, mi, s);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(ms * 1_000_000);
        return t;
    }
}
