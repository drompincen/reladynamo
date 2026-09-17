package com.reladynamo.demo.classifier.util;

import java.sql.Timestamp;

/**
 * Deterministic business-date timestamps. Reladomo as-of predicates are half-open
 * {@code from &lt;= asOf &lt; thru}, so midnight on the stated calendar day is enough.
 */
public final class Dates
{
    public static final Timestamp AS_OF_2012 = at("2012-06-01");
    public static final Timestamp AS_OF_2013 = at("2013-06-01");
    public static final Timestamp AS_OF_2014 = at("2014-06-01");
    public static final Timestamp AS_OF_2016 = at("2016-06-01");
    public static final Timestamp AS_OF_2021 = at("2021-06-01");
    public static final Timestamp TODAY = at("2026-09-12");

    public static final Timestamp FROM_2010 = at("2010-01-01");
    public static final Timestamp FROM_2015 = at("2015-01-01");
    public static final Timestamp FROM_2018 = at("2018-01-01");
    public static final Timestamp FROM_2020 = at("2020-01-01");
    public static final Timestamp FROM_2022 = at("2022-01-01");
    public static final Timestamp TERMINATE_R1_ON = at("2014-01-01");

    public static final Timestamp WRONG_RULE_LEARNED = at("2026-09-12 10:00:00");
    public static final Timestamp WRONG_RULE_EVALUATED = at("2026-09-12 11:00:00");
    public static final Timestamp WRONG_RULE_CORRECTED = at("2026-09-12 12:00:00");
    public static final Timestamp AFTER_CORRECTION = at("2026-09-12 13:00:00");
    public static final Timestamp TERMINATE_PROCESSING = at("2026-09-12 14:00:00");

    private Dates()
    {
    }

    public static Timestamp at(String isoDateOrDateTime)
    {
        if (isoDateOrDateTime.length() == 10)
        {
            return Timestamp.valueOf(isoDateOrDateTime + " 00:00:00.000");
        }
        return Timestamp.valueOf(isoDateOrDateTime);
    }
}
