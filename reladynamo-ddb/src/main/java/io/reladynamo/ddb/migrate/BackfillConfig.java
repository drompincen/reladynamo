package io.reladynamo.ddb.migrate;

/**
 * Operational bounds for a backfill run.
 *
 * <p><b>Memory bound.</b> {@link #maxBufferedRows()} is the write-staging ceiling (default 256).
 * The run holds at most <em>one open logical partition</em> of source rows at a time (all
 * versions of one primary key) plus that staging window. A table of K keys is therefore
 * O(V_max) in source memory, not O(K·V). A single key whose history exceeds the staging
 * ceiling is still processed — a correct group diff needs that partition — but it is never
 * held together with another key.
 *
 * <p>Java 11 baseline.
 */
public final class BackfillConfig {

    public static final int DEFAULT_MAX_BUFFERED_ROWS = 256;
    public static final int MAX_BUFFERED_ROWS_CEILING = 10_000;
    /** Default per-table WCU account quota; a backfill must not pretend to be a flood. */
    public static final int MAX_WRITES_PER_SECOND_CEILING = 40_000;

    private final int maxBufferedRows;
    private final Integer maxWritesPerSecond;
    private final boolean allowEmptySource;
    private final String snapshotId;
    private final BackfillCheckpointStore checkpointStore;

    private BackfillConfig(int maxBufferedRows, Integer maxWritesPerSecond, boolean allowEmptySource,
                           String snapshotId, BackfillCheckpointStore checkpointStore) {
        this.maxBufferedRows = maxBufferedRows;
        this.maxWritesPerSecond = maxWritesPerSecond;
        this.allowEmptySource = allowEmptySource;
        this.snapshotId = snapshotId;
        this.checkpointStore = checkpointStore;
    }

    public static BackfillConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxBufferedRows() {
        return maxBufferedRows;
    }

    /** {@code null} means unlimited. */
    public Integer maxWritesPerSecond() {
        return maxWritesPerSecond;
    }

    public boolean allowEmptySource() {
        return allowEmptySource;
    }

    public String snapshotId() {
        return snapshotId;
    }

    public BackfillCheckpointStore checkpointStore() {
        return checkpointStore;
    }

    public static final class Builder {
        private int maxBufferedRows = DEFAULT_MAX_BUFFERED_ROWS;
        private Integer maxWritesPerSecond;
        private boolean allowEmptySource;
        private String snapshotId;
        private BackfillCheckpointStore checkpointStore;

        private Builder() {
        }

        public Builder maxBufferedRows(int maxBufferedRows) {
            if (maxBufferedRows < 1 || maxBufferedRows > MAX_BUFFERED_ROWS_CEILING) {
                throw new IllegalArgumentException(
                        "maxBufferedRows must be in 1.." + MAX_BUFFERED_ROWS_CEILING
                                + ", not " + maxBufferedRows);
            }
            this.maxBufferedRows = maxBufferedRows;
            return this;
        }

        public Builder maxWritesPerSecond(int maxWritesPerSecond) {
            if (maxWritesPerSecond < 1 || maxWritesPerSecond > MAX_WRITES_PER_SECOND_CEILING) {
                throw new IllegalArgumentException(
                        "writesPerSecond must be in 1.." + MAX_WRITES_PER_SECOND_CEILING
                                + ", not " + maxWritesPerSecond
                                + " — a backfill must not consume a table's entire provisioned capacity");
            }
            this.maxWritesPerSecond = Integer.valueOf(maxWritesPerSecond);
            return this;
        }

        public Builder allowEmptySource(boolean allowEmptySource) {
            this.allowEmptySource = allowEmptySource;
            return this;
        }

        public Builder snapshotId(String snapshotId) {
            this.snapshotId = snapshotId;
            return this;
        }

        public Builder checkpointStore(BackfillCheckpointStore checkpointStore) {
            this.checkpointStore = checkpointStore;
            return this;
        }

        public BackfillConfig build() {
            return new BackfillConfig(maxBufferedRows, maxWritesPerSecond, allowEmptySource,
                    snapshotId, checkpointStore);
        }
    }
}
