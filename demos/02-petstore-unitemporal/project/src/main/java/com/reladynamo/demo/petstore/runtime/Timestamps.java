package com.reladynamo.demo.petstore.runtime;

import com.gs.fw.common.mithra.util.DefaultInfinityTimestamp;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Deterministic UTC timestamps used by seed data and the six demonstrations.
 * Infinity is Reladomo's sentinel {@code 9999-12-01 23:59:00.000}.
 */
public final class Timestamps {

    private Timestamps() {
    }

    public static Timestamp infinity() {
        return DefaultInfinityTimestamp.getDefaultInfinity();
    }

    public static Timestamp utcDate(int year, int month, int day) {
        return Timestamp.from(LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    public static Timestamp utcDateTime(int year, int month, int day, int hour, int minute) {
        return Timestamp.from(LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC));
    }

    public static final Timestamp DAY_2024_01_01 = utcDate(2024, 1, 1);
    public static final Timestamp DAY_2025_01_01 = utcDate(2025, 1, 1);
    public static final Timestamp DAY_2025_03_01 = utcDate(2025, 3, 1);
    public static final Timestamp DAY_2025_05_01 = utcDate(2025, 5, 1);
    public static final Timestamp DAY_2025_06_15 = utcDate(2025, 6, 15);
    public static final Timestamp DAY_2025_07_01 = utcDate(2025, 7, 1);
    public static final Timestamp DAY_2025_08_15 = utcDate(2025, 8, 15);
    public static final Timestamp DAY_2025_09_01 = utcDate(2025, 9, 1);
    public static final Timestamp DAY_2025_10_01 = utcDate(2025, 10, 1);
    public static final Timestamp DAY_2025_08_14 = utcDate(2025, 8, 14);
    public static final Timestamp DAY_2025_09_30 = utcDate(2025, 9, 30);
    public static final Timestamp DAY_2025_11_01 = utcDate(2025, 11, 1);
    public static final Timestamp DAY_2026_01_01 = utcDate(2026, 1, 1);

    /** Seed processing instant — all audit-only inserts in the seed share this IN_Z. */
    public static final Timestamp PROC_SEED = utcDateTime(2025, 1, 15, 9, 0);
    public static final Timestamp PROC_PAID = utcDateTime(2025, 6, 1, 10, 0);
    public static final Timestamp PROC_FULFILLED = utcDateTime(2025, 7, 1, 10, 0);
    public static final Timestamp PROC_ADOPTION = utcDateTime(2025, 8, 15, 12, 0);
}
