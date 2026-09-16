package com.reladynamo.demo.crm.util;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Reladomo infinity sentinel: 9999-12-01 23:59:00.000 UTC, matching the entity spec.
 */
public final class InfinityTimestamp
{
    private static final long INFINITY_MILLIS = buildInfinityMillis();

    private InfinityTimestamp()
    {
    }

    public static Timestamp getInfinityDate()
    {
        return new Timestamp(INFINITY_MILLIS);
    }

    private static long buildInfinityMillis()
    {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.set(Calendar.YEAR, 9999);
        calendar.set(Calendar.MONTH, Calendar.DECEMBER);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
