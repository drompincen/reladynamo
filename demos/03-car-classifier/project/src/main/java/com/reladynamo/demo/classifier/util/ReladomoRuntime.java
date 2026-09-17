package com.reladynamo.demo.classifier.util;

import com.gs.fw.common.mithra.MithraManager;
import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.util.MithraRuntimeCacheController;
import com.reladynamo.demo.classifier.Classifier;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.TimeZone;

/**
 * Loads Reladomo against in-memory H2 and can wipe tables between tests.
 */
public final class ReladomoRuntime
{
    private static final String RUNTIME_XML = "reladomo/config/ReladomoRuntimeConfig.xml";
    private static volatile boolean started;

    private ReladomoRuntime()
    {
    }

    public static synchronized void start()
    {
        if (started)
        {
            return;
        }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        H2ConnectionManager.getInstance().prepareTables();
        MithraManager manager = MithraManagerProvider.getMithraManager();
        manager.setTransactionTimeout(120);
        InputStream in = ReladomoRuntime.class.getClassLoader().getResourceAsStream(RUNTIME_XML);
        if (in == null)
        {
            throw new IllegalStateException("Missing Reladomo runtime XML: " + RUNTIME_XML);
        }
        try
        {
            manager.readConfiguration(in);
        }
        finally
        {
            try
            {
                in.close();
            }
            catch (IOException ignored)
            {
            }
        }
        started = true;
    }

    public static void wipeData()
    {
        try
        {
            Connection connection = H2ConnectionManager.getInstance().getConnection();
            try
            {
                connection.setAutoCommit(true);
                Statement statement = connection.createStatement();
                try
                {
                    statement.executeUpdate("DELETE FROM CLASSIFICATION_RESULT");
                    statement.executeUpdate("DELETE FROM RULE_CRITERION");
                    statement.executeUpdate("DELETE FROM CLASSIFICATION_RULE");
                    statement.executeUpdate("DELETE FROM CAR");
                    statement.executeUpdate("DELETE FROM RESULT_LABEL");
                }
                finally
                {
                    statement.close();
                }
            }
            finally
            {
                connection.close();
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to wipe H2 tables", e);
        }

        MithraManager manager = MithraManagerProvider.getMithraManager();
        manager.clearAllQueryCaches();
        for (MithraRuntimeCacheController controller : manager.getRuntimeCacheControllerSet())
        {
            controller.clearPartialCacheOrReloadFullCache();
        }
        Classifier.resetResultIds();
    }
}
