package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.MithraManagerProvider;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Boots Reladomo over in-memory H2 so generated finders have real portals.
 *
 * <p>Stub portals do not survive {@code AnalyzedOperation}: Reladomo walks class metadata,
 * cache, and index references. Pattern copied from {@code SpikeTestSupport}.
 */
final class PlanReladomoBoot {

    private static final AtomicBoolean BOOTED = new AtomicBoolean();

    private PlanReladomoBoot() {
    }

    static void ensure() {
        if (!BOOTED.compareAndSet(false, true)) {
            return;
        }
        try {
            createSchema();
            try (InputStream in = PlanReladomoBoot.class.getResourceAsStream("/reladomo/PlanRuntime.xml")) {
                if (in == null) {
                    throw new IllegalStateException("missing classpath resource /reladomo/PlanRuntime.xml");
                }
                MithraManagerProvider.getMithraManager().readConfiguration(in);
            }
        } catch (Exception e) {
            BOOTED.set(false);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new IllegalStateException("failed to boot Reladomo over H2 for planner tests", e);
        }
    }

    private static void createSchema() throws Exception {
        StringBuilder ddl = new StringBuilder();
        try (InputStream in = PlanReladomoBoot.class.getResourceAsStream("/reladomo/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource /reladomo/schema.sql");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                ddl.append(line).append('\n');
            }
        }
        try (Connection c = H2ConnectionManager.getInstance().getConnection();
             Statement s = c.createStatement()) {
            String[] stmts = ddl.toString().split(";");
            for (int i = 0; i < stmts.length; i++) {
                if (stmts[i].trim().length() > 0) {
                    s.execute(stmts[i]);
                }
            }
        }
    }
}
