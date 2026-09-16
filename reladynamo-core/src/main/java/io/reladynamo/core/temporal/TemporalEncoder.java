package io.reladynamo.core.temporal;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Encodes a {@link Timestamp} into a fixed-width, UTC, lexicographically sortable string suitable
 * for a DynamoDB sort key component.
 *
 * <p>Three properties matter, and all three are load-bearing:
 *
 * <ul>
 *   <li><b>Fixed width.</b> DynamoDB compares sort keys as byte strings. A ragged encoding makes
 *       {@code BETWEEN} return the wrong rows rather than fail, so width is not cosmetic.
 *   <li><b>Lexicographic order equals chronological order</b>, including before the epoch. This is
 *       what lets a temporal predicate become a native range condition instead of a filter.
 *   <li><b>Exact round trip.</b> Reladomo compares its infinity sentinel <em>by value</em>; an
 *       encoding that returns a near-miss silently breaks every current-row query.
 * </ul>
 *
 * <p>Pre-epoch instants are the subtle case. A signed millisecond value sorts incorrectly as text
 * because "-1" &gt; "-2" lexicographically while -1 &gt; -2 chronologically. Rather than encode the
 * epoch offset, this encodes the civil UTC field values, which are monotonic across the epoch.
 *
 * <p>Java 11 baseline: no records, no text blocks, no {@code java.time} formatter caching tricks
 * that would pull in newer APIs.
 */
public final class TemporalEncoder {

    /** {@code yyyyMMddHHmmssSSS} — 17 characters, zero-padded, always UTC. */
    public static final int WIDTH = 17;

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private TemporalEncoder() {
    }

    public static String encode(Timestamp value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "cannot encode a null timestamp: a temporal key component is never optional");
        }
        Calendar cal = new GregorianCalendar(UTC);
        cal.setTimeInMillis(value.getTime());

        StringBuilder sb = new StringBuilder(WIDTH);
        pad(sb, cal.get(Calendar.YEAR), 4);
        pad(sb, cal.get(Calendar.MONTH) + 1, 2);
        pad(sb, cal.get(Calendar.DAY_OF_MONTH), 2);
        pad(sb, cal.get(Calendar.HOUR_OF_DAY), 2);
        pad(sb, cal.get(Calendar.MINUTE), 2);
        pad(sb, cal.get(Calendar.SECOND), 2);
        // Milliseconds come from nanos, not from the calendar: Timestamp keeps sub-second precision
        // in its nanos field, and getTime() is only millisecond-accurate for the epoch offset.
        pad(sb, value.getNanos() / 1_000_000, 3);
        return sb.toString();
    }

    public static Timestamp decode(String encoded) {
        if (encoded == null || encoded.length() != WIDTH) {
            throw new IllegalArgumentException(
                    "expected a " + WIDTH + "-character temporal key component, got: " + encoded);
        }
        int year = parse(encoded, 0, 4);
        int month = parse(encoded, 4, 6);
        int day = parse(encoded, 6, 8);
        int hour = parse(encoded, 8, 10);
        int minute = parse(encoded, 10, 12);
        int second = parse(encoded, 12, 14);
        int millis = parse(encoded, 14, 17);

        Calendar cal = new GregorianCalendar(UTC);
        cal.clear();
        cal.set(year, month - 1, day, hour, minute, second);
        Timestamp result = new Timestamp(cal.getTimeInMillis());
        result.setNanos(millis * 1_000_000);
        return result;
    }

    private static void pad(StringBuilder sb, int value, int width) {
        String s = Integer.toString(value);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
    }

    private static int parse(String s, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("non-numeric temporal key component: " + s);
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }
}
