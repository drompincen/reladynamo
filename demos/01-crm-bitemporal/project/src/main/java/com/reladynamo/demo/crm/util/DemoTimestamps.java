package com.reladynamo.demo.crm.util;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Deterministic UTC timestamps. No wall-clock reads.
 */
public final class DemoTimestamps
{
    /** Start of the modelled world. */
    public static final Timestamp B_START = utc(2025, 1, 1);

    /** Territory reassignment becomes true in the real world. */
    public static final Timestamp B_TERRITORY = utc(2025, 2, 1);

    /** Acme HQ move / legal-name change becomes true. */
    public static final Timestamp B_MOVE = utc(2025, 3, 1);

    /** Consent withdrawal effective date (GDPR). */
    public static final Timestamp B_WITHDRAW = utc(2025, 3, 15);

    /** Shared as-of business date for address, consent, and pipeline queries. */
    public static final Timestamp B_QUERY_APR = utc(2025, 4, 1);

    /** Promotional price window: [start, end). */
    public static final Timestamp B_PROMO_START = utc(2025, 4, 1);
    public static final Timestamp B_PROMO_END = utc(2025, 7, 1);

    public static final Timestamp B_REVENUE = utc(2025, 5, 1);
    public static final Timestamp B_CLOSE = utc(2025, 5, 15);

    /** Surcharge window after the promo: [start, end). */
    public static final Timestamp B_SURCHARGE_START = utc(2025, 7, 1);
    public static final Timestamp B_SURCHARGE_END = utc(2025, 8, 1);

    public static final Timestamp B_SUB_END = utc(2025, 8, 15);
    public static final Timestamp B_CAROL_TERM = utc(2025, 8, 31);

    public static final Timestamp B_BEFORE_SUB_END = utc(2025, 8, 1);
    public static final Timestamp B_AFTER_SUB_END = utc(2025, 8, 16);
    public static final Timestamp B_BEFORE_CAROL_TERM = utc(2025, 8, 1);
    public static final Timestamp B_AFTER_CAROL_TERM = utc(2025, 9, 1);

    public static final Timestamp B_PRE_PROMO = utc(2025, 2, 1);
    public static final Timestamp B_IN_PROMO = utc(2025, 5, 1);
    public static final Timestamp B_IN_SURCHARGE = utc(2025, 7, 15);
    public static final Timestamp B_POST_SURCHARGE = utc(2025, 9, 1);

    /** Processing (knowledge) timeline. */
    public static final Timestamp P_INITIAL = utc(2025, 1, 2, 9, 0, 0);
    public static final Timestamp P_RENAME = utc(2025, 3, 10, 9, 0, 0);
    public static final Timestamp P_PRICES = utc(2025, 3, 20, 9, 0, 0);

    /** "As we knew it on 2025-04-02" - after Q1 reporting, before late corrections. */
    public static final Timestamp P_KNOWN_APR = utc(2025, 4, 2, 9, 0, 0);

    public static final Timestamp P_REVENUE = utc(2025, 5, 1, 9, 0, 0);
    public static final Timestamp P_CLOSE = utc(2025, 5, 15, 9, 0, 0);
    public static final Timestamp P_ADDRESS = utc(2025, 6, 15, 9, 0, 0);
    public static final Timestamp P_CONSENT = utc(2025, 7, 1, 9, 0, 0);
    public static final Timestamp P_RESTATE = utc(2025, 8, 1, 9, 0, 0);
    public static final Timestamp P_TERM = utc(2025, 9, 1, 9, 0, 0);

    /** "As we know it today" for the demo. */
    public static final Timestamp P_TODAY = utc(2025, 10, 1, 9, 0, 0);

    public static final Timestamp OUTREACH_SENT = utc(2025, 4, 1, 14, 0, 0);

    private DemoTimestamps()
    {
    }

    public static Timestamp utc(int year, int month, int day)
    {
        return utc(year, month, day, 0, 0, 0);
    }

    public static Timestamp utc(int year, int month, int day, int hour, int minute, int second)
    {
        long millis = LocalDateTime.of(year, month, day, hour, minute, second)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
        return new Timestamp(millis);
    }

    public static Timestamp infinity()
    {
        return InfinityTimestamp.getInfinityDate();
    }
}
