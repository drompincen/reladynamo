package com.reladynamo.demo.crm.util;

import com.gs.fw.common.mithra.bulkloader.BulkLoader;
import com.gs.fw.common.mithra.bulkloader.BulkLoaderException;
import com.gs.fw.common.mithra.connectionmanager.SourcelessConnectionManager;
import com.gs.fw.common.mithra.connectionmanager.XAConnectionManager;
import com.gs.fw.common.mithra.databasetype.DatabaseType;
import com.gs.fw.common.mithra.databasetype.H2DatabaseType;
import org.h2.tools.RunScript;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.TimeZone;

public final class H2ConnectionManager implements SourcelessConnectionManager
{
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static H2ConnectionManager instance;

    private final XAConnectionManager xaConnectionManager;

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
        xaConnectionManager.setJdbcConnectionString(
                "jdbc:h2:mem:crm;DB_CLOSE_DELAY=-1;MODE=LEGACY;DATABASE_TO_UPPER=TRUE");
        xaConnectionManager.setJdbcUser("sa");
        xaConnectionManager.setJdbcPassword("");
        xaConnectionManager.setPoolName("crm-bitemporal-h2");
        xaConnectionManager.setInitialSize(1);
        xaConnectionManager.setPoolSize(10);
        xaConnectionManager.setMaxWait(500);
        xaConnectionManager.initialisePool();
    }

    @Override
    public Connection getConnection()
    {
        return xaConnectionManager.getConnection();
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
        return "crm";
    }

    public void prepareTables()
    {
        java.io.InputStream in = H2ConnectionManager.class.getClassLoader().getResourceAsStream("h2/schema.sql");
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

    public void shutdown()
    {
        xaConnectionManager.shutdown();
    }
}
