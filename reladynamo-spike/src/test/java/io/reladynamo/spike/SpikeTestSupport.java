package io.reladynamo.spike;

import com.gs.fw.common.mithra.MithraManagerProvider;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Boots H2 and Reladomo once for the spike tests. */
final class SpikeTestSupport {
    static final Timestamp BUSINESS_DATE = utc(2026, Calendar.JUNE, 1);
    private static final AtomicBoolean BOOTED = new AtomicBoolean();

    private SpikeTestSupport() {
    }

    static void boot() throws Exception {
        if (!BOOTED.compareAndSet(false, true)) {
            return;
        }
        createSchema();
        try (InputStream in = SpikeTestSupport.class.getResourceAsStream("/SpikeRuntime.xml")) {
            MithraManagerProvider.getMithraManager().readConfiguration(in);
        }
    }

    private static void createSchema() throws Exception {
        StringBuilder ddl = new StringBuilder();
        try (InputStream in = SpikeTestSupport.class.getResourceAsStream("/schema.sql");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                ddl.append(line).append('\n');
            }
        }
        try (Connection c = H2ConnectionManager.getInstance().getConnection();
             Statement s = c.createStatement()) {
            for (String stmt : ddl.toString().split(";")) {
                if (stmt.trim().length() > 0) {
                    s.execute(stmt);
                }
            }
        }
    }

    private static Timestamp utc(int year, int month, int day) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(year, month, day, 0, 0, 0);
        return new Timestamp(cal.getTimeInMillis());
    }
}
