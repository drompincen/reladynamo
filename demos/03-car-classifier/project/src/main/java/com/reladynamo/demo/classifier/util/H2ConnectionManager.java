package com.reladynamo.demo.classifier.util;

import com.gs.fw.common.mithra.bulkloader.BulkLoader;
import com.gs.fw.common.mithra.bulkloader.BulkLoaderException;
import com.gs.fw.common.mithra.connectionmanager.SourcelessConnectionManager;
import com.gs.fw.common.mithra.connectionmanager.XAConnectionManager;
import com.gs.fw.common.mithra.databasetype.DatabaseType;
import com.gs.fw.common.mithra.databasetype.H2DatabaseType;
import org.h2.tools.RunScript;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.TimeZone;

/**
 * In-memory H2 connection manager for the demo. Production Reladomo apps typically
 * point this at a pooled physical database instead of creating tables on startup.
 */
public final class H2ConnectionManager implements SourcelessConnectionManager
{
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final String JDBC_URL =
            "jdbc:h2:mem:carclassifier;DB_CLOSE_DELAY=-1;MODE=LEGACY;DATABASE_TO_UPPER=TRUE";

    private static H2ConnectionManager instance;

    private final XAConnectionManager xaConnectionManager;
    private volatile boolean disconnected;

    public static synchronized H2ConnectionManager getInstance()
    {
        if (instance == null)
        {
            instance = new H2ConnectionManager();
        }
        return instance;
    }

    private H2ConnectionManager()
    {
        xaConnectionManager = new XAConnectionManager();
        xaConnectionManager.setDriverClassName("org.h2.Driver");
        xaConnectionManager.setMaxWait(500);
        xaConnectionManager.setJdbcConnectionString(JDBC_URL);
        xaConnectionManager.setJdbcUser("sa");
        xaConnectionManager.setJdbcPassword("");
        xaConnectionManager.setPoolName("car-classifier-h2");
        xaConnectionManager.setInitialSize(1);
        xaConnectionManager.setPoolSize(10);
        xaConnectionManager.initialisePool();
    }

    @Override
    public Connection getConnection()
    {
        if (disconnected)
        {
            throw new IllegalStateException(
                    "H2 is disconnected for bound-portal measurement; a JDBC fallback is a harness failure");
        }
        return xaConnectionManager.getConnection();
    }

    public void disconnectRelationalSource()
    {
        disconnected = true;
    }

    public void reconnectRelationalSource()
    {
        disconnected = false;
    }

    @Override
    public DatabaseType getDatabaseType()
    {
        return H2DatabaseType.getInstance();
    }

    @Override
    public TimeZone getDatabaseTimeZone()
    {
        return UTC;
    }

    @Override
    public BulkLoader createBulkLoader() throws BulkLoaderException
    {
        throw new RuntimeException("BulkLoader is not used in this demo");
    }

    @Override
    public String getDatabaseIdentifier()
    {
        return "carclassifier";
    }

    public void prepareTables()
    {
        InputStream in = H2ConnectionManager.class.getClassLoader().getResourceAsStream("h2/schema.sql");
        if (in == null)
        {
            throw new IllegalStateException("Missing classpath resource h2/schema.sql");
        }
        try (Connection connection = getConnection();
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
        {
            RunScript.execute(connection, reader);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to apply H2 schema", e);
        }
    }
}
