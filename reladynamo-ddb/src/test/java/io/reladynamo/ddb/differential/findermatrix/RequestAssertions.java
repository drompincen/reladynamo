package io.reladynamo.ddb.differential.findermatrix;

/**
 * Cold-cache proof. A Reladomo cache hit makes a MATCH look green without the adapter
 * running; these assertions refuse that theatre. Counters increment on request entry.
 */
final class RequestAssertions {

    private RequestAssertions() {
    }

    static void requirePositiveDataReads(int delta, String phase) {
        if (delta <= 0) {
            throw new AssertionError("DDB data-read request delta was " + delta
                    + " during " + phase
                    + "; the finder was served without reaching DynamoDB "
                    + "(Reladomo cache hit, planner short-circuit, or missing bind)");
        }
    }

    static void requireZeroScan(int scanDelta, String phase) {
        if (scanDelta != 0) {
            throw new AssertionError("Scan delta was " + scanDelta + " during " + phase
                    + "; the matrix forbids Scan as an access path");
        }
    }

    static void requireReaderEntered(int findOrCountDelta, String phase) {
        if (findOrCountDelta < 1) {
            throw new AssertionError("bound reader find/count entry delta was "
                    + findOrCountDelta + " during " + phase
                    + "; Reladomo did not call the adapter");
        }
    }

    static void requireH2JdbcRead(int selectDelta, String phase) {
        if (selectDelta < 1) {
            throw new AssertionError("H2 JDBC SELECT delta was " + selectDelta
                    + " during " + phase
                    + "; a warmed DDB object must not become the H2 oracle");
        }
    }

    static void requireNoSqlDuringDdb(int selectDelta, String phase) {
        if (selectDelta != 0) {
            throw new AssertionError("SQL SELECT delta was " + selectDelta
                    + " during DDB measurement of " + phase
                    + "; fallback JDBC is a harness failure");
        }
    }
}
