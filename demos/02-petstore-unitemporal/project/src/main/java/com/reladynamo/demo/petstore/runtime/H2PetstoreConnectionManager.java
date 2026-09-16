package com.reladynamo.demo.petstore.runtime;

import com.gs.fw.common.mithra.bulkloader.BulkLoader;
import com.gs.fw.common.mithra.bulkloader.BulkLoaderException;
import com.gs.fw.common.mithra.connectionmanager.SourcelessConnectionManager;
import com.gs.fw.common.mithra.connectionmanager.XAConnectionManager;
import com.gs.fw.common.mithra.databasetype.DatabaseType;
import com.gs.fw.common.mithra.databasetype.H2DatabaseType;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.TimeZone;
import org.h2.tools.RunScript;

/**
 * Reladomo {@link SourcelessConnectionManager} wrapping {@link XAConnectionManager} against an
 * in-memory H2 database. Reladomo instantiates this via the public static {@code getInstance()}
 * method declared in {@code MithraRuntime.xml}.
 */
public final class H2PetstoreConnectionManager implements SourcelessConnectionManager {

    public static final String JDBC_URL =
            "jdbc:h2:mem:petstore;DB_CLOSE_DELAY=-1;MODE=LEGACY;DATABASE_TO_UPPER=TRUE";

    private static final H2PetstoreConnectionManager INSTANCE = new H2PetstoreConnectionManager();

    private final XAConnectionManager xaConnectionManager;
    private volatile boolean disconnected;

    public static H2PetstoreConnectionManager getInstance() {
        return INSTANCE;
    }

    public void disconnectRelationalSource() {
        disconnected = true;
    }

    public void reconnectRelationalSource() {
        disconnected = false;
    }

    private H2PetstoreConnectionManager() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        xaConnectionManager = new XAConnectionManager();
        xaConnectionManager.setDriverClassName("org.h2.Driver");
        xaConnectionManager.setJdbcConnectionString(JDBC_URL);
        xaConnectionManager.setJdbcUser("sa");
        xaConnectionManager.setJdbcPassword("");
        xaConnectionManager.setPoolName("petstore-h2");
        xaConnectionManager.setInitialSize(1);
        xaConnectionManager.setPoolSize(8);
        xaConnectionManager.setUseStatementPooling(true);
        xaConnectionManager.initialisePool();
    }

    public void ensureSchema() {
        InputStream in = H2PetstoreConnectionManager.class.getResourceAsStream("/h2/schema.sql");
        if (in == null) {
            throw new IllegalStateException("missing classpath resource /h2/schema.sql");
        }
        try (Connection connection = getConnection();
                InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        } catch (Exception e) {
            throw new IllegalStateException("failed to apply H2 schema", e);
        }
    }

    @Override
    public BulkLoader createBulkLoader() throws BulkLoaderException {
        throw new BulkLoaderException("BulkLoader is not used in this demo");
    }

    @Override
    public Connection getConnection() {
        if (disconnected) {
            throw new IllegalStateException(
                    "H2 is disconnected for bound-portal measurement; a JDBC fallback is a harness failure");
        }
        return xaConnectionManager.getConnection();
    }

    @Override
    public DatabaseType getDatabaseType() {
        return H2DatabaseType.getInstance();
    }

    @Override
    public TimeZone getDatabaseTimeZone() {
        return TimeZone.getTimeZone("UTC");
    }

    @Override
    public String getDatabaseIdentifier() {
        return "petstore";
    }
}
