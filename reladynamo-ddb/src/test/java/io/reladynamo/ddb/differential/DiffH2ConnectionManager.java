package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.bulkloader.BulkLoader;
import com.gs.fw.common.mithra.bulkloader.BulkLoaderException;
import com.gs.fw.common.mithra.connectionmanager.SourcelessConnectionManager;
import com.gs.fw.common.mithra.databasetype.DatabaseType;
import com.gs.fw.common.mithra.databasetype.H2DatabaseType;
import io.reladynamo.ddb.differential.findermatrix.SqlReadProbe;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.TimeZone;

/** H2 is the reference implementation the DynamoDB adapter is diffed against. */
public class DiffH2ConnectionManager implements SourcelessConnectionManager {
    private static final DiffH2ConnectionManager INSTANCE = new DiffH2ConnectionManager();
    private static final String URL = "jdbc:h2:mem:diffbalance;DB_CLOSE_DELAY=-1";
    private static final ThreadLocal<SqlReadProbe> SQL_PROBE = new ThreadLocal<SqlReadProbe>();
    private static volatile boolean disconnected;

    public static DiffH2ConnectionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Genuinely disconnect the relational source. Not "unused": the next
     * {@link #getConnection()} throws, so a JDBC fallback cannot silently serve the query.
     */
    public static void disconnectRelationalSource() {
        disconnected = true;
    }

    public static void reconnectRelationalSource() {
        disconnected = false;
    }

    /** Finder-matrix hook: wrap JDBC so DDB measurement can forbid fallback SELECTs. */
    public static void installSqlProbe(SqlReadProbe probe) {
        if (probe == null) {
            SQL_PROBE.remove();
        } else {
            SQL_PROBE.set(probe);
        }
    }

    @Override
    public Connection getConnection() {
        if (disconnected) {
            throw new IllegalStateException(
                    "H2 is disconnected for bound-portal measurement; a JDBC fallback is a harness failure");
        }
        try {
            Connection connection = DriverManager.getConnection(URL, "sa", "");
            SqlReadProbe probe = SQL_PROBE.get();
            if (probe != null) {
                return probe.wrap(connection);
            }
            return connection;
        } catch (SQLException e) {
            throw new RuntimeException("could not open the H2 reference connection", e);
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
        return "diffbalance";
    }

    @Override
    public BulkLoader createBulkLoader() throws BulkLoaderException {
        throw new BulkLoaderException("no bulk loader in the differential suite");
    }
}
