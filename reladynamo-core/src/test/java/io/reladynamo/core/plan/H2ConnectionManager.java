package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.bulkloader.BulkLoader;
import com.gs.fw.common.mithra.bulkloader.BulkLoaderException;
import com.gs.fw.common.mithra.connectionmanager.SourcelessConnectionManager;
import com.gs.fw.common.mithra.databasetype.DatabaseType;
import com.gs.fw.common.mithra.databasetype.H2DatabaseType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.TimeZone;

/** In-memory H2 connection manager for planner fixture boot. Reladomo calls {@link #getInstance()}. */
public final class H2ConnectionManager implements SourcelessConnectionManager {

    private static final H2ConnectionManager INSTANCE = new H2ConnectionManager();
    private static final String URL = "jdbc:h2:mem:reladynamo_plan;DB_CLOSE_DELAY=-1";

    public static H2ConnectionManager getInstance() {
        return INSTANCE;
    }

    private H2ConnectionManager() {
    }

    @Override
    public Connection getConnection() {
        try {
            return DriverManager.getConnection(URL, "sa", "");
        } catch (SQLException e) {
            throw new RuntimeException("could not open planner H2 connection", e);
        }
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
        return "reladynamo_plan";
    }

    @Override
    public BulkLoader createBulkLoader() throws BulkLoaderException {
        throw new BulkLoaderException("no bulk loader in planner tests");
    }
}
