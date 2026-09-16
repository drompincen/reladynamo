package io.reladynamo.spike;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * The bitemporal infinity sentinel. Reladomo compares against this exact instance value, so it must
 * be a single fixed constant — a drifting "far future" computed at runtime silently breaks every
 * current-row query.
 */
public final class SpikeInfinity {
    public static final Timestamp INFINITY = buildInfinity();

    private SpikeInfinity() {
    }

    private static Timestamp buildInfinity() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(9999, Calendar.DECEMBER, 1, 23, 59, 0);
        return new Timestamp(cal.getTimeInMillis());
    }
}
