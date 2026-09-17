package io.reladynamo.ddb.codec;

import java.sql.Date;
import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * UTC civil-date and time-of-day wire forms. Timestamps go through
 * {@link io.reladynamo.core.temporal.TemporalEncoder}; this class is only Date and Time.
 *
 * <p>Never uses {@code Date.valueOf} / {@code Time.valueOf} — those parse in the JVM default
 * timezone and will disagree with UTC storage the moment CI is not UTC.
 */
final class DateTimeWire {

    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter TIME_OF_DAY =
            new DateTimeFormatterBuilder()
                    .appendPattern("HH:mm:ss")
                    .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
                    .toFormatter();

    private static final DateTimeFormatter TIME_OF_DAY_UTC =
            TIME_OF_DAY.withZone(ZoneOffset.UTC);

    private DateTimeWire() {
    }

    static String encodeSqlDate(Date date) {
        Instant instant = Instant.ofEpochMilli(date.getTime());
        return ISO_DATE.format(instant);
    }

    static Date decodeSqlDate(String wire) {
        LocalDate localDate = LocalDate.parse(wire);
        long millis = localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return new Date(millis);
    }

    static String encodeSqlTime(Time time) {
        Instant instant = Instant.ofEpochMilli(time.getTime());
        return TIME_OF_DAY_UTC.format(instant);
    }

    static Time decodeSqlTime(String wire) {
        LocalTime localTime = LocalTime.parse(wire, TIME_OF_DAY);
        long millis = localTime.toNanoOfDay() / 1_000_000L;
        return new Time(millis);
    }

    static String encodeReladomoTime(com.gs.fw.common.mithra.util.Time time) {
        return String.format(
                java.util.Locale.ROOT,
                "%02d:%02d:%02d.%03d",
                (int) time.getHour(),
                (int) time.getMinute(),
                (int) time.getSecond(),
                time.getMillisecond());
    }

    static com.gs.fw.common.mithra.util.Time decodeReladomoTime(String wire) {
        LocalTime localTime = LocalTime.parse(wire, TIME_OF_DAY);
        return com.gs.fw.common.mithra.util.Time.withMillis(
                localTime.getHour(),
                localTime.getMinute(),
                localTime.getSecond(),
                localTime.getNano() / 1_000_000);
    }
}
