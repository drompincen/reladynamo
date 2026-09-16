package io.reladynamo.core.plan;

/**
 * Planner knobs. Defaults match the Chapter 4 design. The planner is a pure function of this
 * config plus the operation — it does not read thread locals or system time.
 */
public final class PlannerConfig {

    public static final int DEFAULT_PK_FAN_OUT_LIMIT = 100;
    public static final int HARD_MAX_PK_FAN_OUT_LIMIT = 1000;
    public static final int DEFAULT_JOIN_FAN_OUT_LIMIT = 1000;
    public static final int DEFAULT_PARALLEL_SCAN_SEGMENTS = 4;
    public static final int DEFAULT_MAX_PAGES = 64;
    public static final int DEFAULT_IN_MEMORY_ROW_CEILING = 50_000;
    public static final int DEFAULT_IN_MEMORY_BYTE_CEILING = 32 * 1024 * 1024;
    public static final int DEFAULT_ESTIMATED_VERSIONS_PER_KEY = 16;
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int DEFAULT_AVG_ITEM_BYTES = 1024;
    public static final int DYNAMO_FILTER_IN_CAP = 100;

    private final boolean allowTableScan;
    private final int pkFanOutLimit;
    private final int joinFanOutLimit;
    private final int parallelScanSegments;
    private final int maxPages;
    private final int inMemoryRowCeiling;
    private final int inMemoryByteCeiling;
    private final int estimatedVersionsPerKey;
    private final boolean allowGsi;
    private final boolean inTransaction;
    private final boolean allowScanDelete;
    private final boolean allowFullCacheLoad;
    private final int pageSize;
    private final int avgItemBytes;

    private PlannerConfig(Builder builder) {
        this.allowTableScan = builder.allowTableScan;
        this.pkFanOutLimit = builder.pkFanOutLimit;
        this.joinFanOutLimit = builder.joinFanOutLimit;
        this.parallelScanSegments = builder.parallelScanSegments;
        this.maxPages = builder.maxPages;
        this.inMemoryRowCeiling = builder.inMemoryRowCeiling;
        this.inMemoryByteCeiling = builder.inMemoryByteCeiling;
        this.estimatedVersionsPerKey = builder.estimatedVersionsPerKey;
        this.allowGsi = builder.allowGsi;
        this.inTransaction = builder.inTransaction;
        this.allowScanDelete = builder.allowScanDelete;
        this.allowFullCacheLoad = builder.allowFullCacheLoad;
        this.pageSize = builder.pageSize;
        this.avgItemBytes = builder.avgItemBytes;
    }

    public static PlannerConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean allowTableScan() {
        return allowTableScan;
    }

    public int pkFanOutLimit() {
        return pkFanOutLimit;
    }

    public int joinFanOutLimit() {
        return joinFanOutLimit;
    }

    public int parallelScanSegments() {
        return parallelScanSegments;
    }

    public int maxPages() {
        return maxPages;
    }

    public int inMemoryRowCeiling() {
        return inMemoryRowCeiling;
    }

    public int inMemoryByteCeiling() {
        return inMemoryByteCeiling;
    }

    public int estimatedVersionsPerKey() {
        return estimatedVersionsPerKey;
    }

    public boolean allowGsi() {
        return allowGsi;
    }

    public boolean inTransaction() {
        return inTransaction;
    }

    public boolean allowScanDelete() {
        return allowScanDelete;
    }

    public boolean allowFullCacheLoad() {
        return allowFullCacheLoad;
    }

    public int pageSize() {
        return pageSize;
    }

    public int avgItemBytes() {
        return avgItemBytes;
    }

    public static final class Builder {
        private boolean allowTableScan = false;
        private int pkFanOutLimit = DEFAULT_PK_FAN_OUT_LIMIT;
        private int joinFanOutLimit = DEFAULT_JOIN_FAN_OUT_LIMIT;
        private int parallelScanSegments = DEFAULT_PARALLEL_SCAN_SEGMENTS;
        private int maxPages = DEFAULT_MAX_PAGES;
        private int inMemoryRowCeiling = DEFAULT_IN_MEMORY_ROW_CEILING;
        private int inMemoryByteCeiling = DEFAULT_IN_MEMORY_BYTE_CEILING;
        private int estimatedVersionsPerKey = DEFAULT_ESTIMATED_VERSIONS_PER_KEY;
        private boolean allowGsi = true;
        private boolean inTransaction = false;
        private boolean allowScanDelete = false;
        private boolean allowFullCacheLoad = false;
        private int pageSize = DEFAULT_PAGE_SIZE;
        private int avgItemBytes = DEFAULT_AVG_ITEM_BYTES;

        public Builder allowTableScan(boolean v) {
            this.allowTableScan = v;
            return this;
        }

        public Builder pkFanOutLimit(int v) {
            if (v < 1 || v > HARD_MAX_PK_FAN_OUT_LIMIT) {
                throw new IllegalArgumentException(
                        "pkFanOutLimit must be 1.." + HARD_MAX_PK_FAN_OUT_LIMIT + ", got " + v);
            }
            this.pkFanOutLimit = v;
            return this;
        }

        /**
         * <b>Not implemented.</b> design 04 specifies a `PLAN-003` overflow when a join fans out past this; the planner never consults it.
         *
         * <p>Setting it to anything but the default is refused rather than silently ignored. A knob
         * that reads as a guarantee and does nothing is worse than a missing one — that is finding 16,
         * where a documented denial-of-service mitigation turned out to be documentation only.
         *
         * @throws UnsupportedOperationException for any value other than {@link #DEFAULT_JOIN_FAN_OUT_LIMIT}
         */
        public Builder joinFanOutLimit(int v) {
            if (v != DEFAULT_JOIN_FAN_OUT_LIMIT) {
                throw new UnsupportedOperationException(
                        "joinFanOutLimit is declared but not yet enforced, so setting it would imply a "
                                + "guarantee that does not exist. Leave it at the default ("
                                + DEFAULT_JOIN_FAN_OUT_LIMIT + ") until the planner consults it. See finding 16.");
            }
            this.joinFanOutLimit = v;
            return this;
        }

        public Builder parallelScanSegments(int v) {
            this.parallelScanSegments = v;
            return this;
        }

        public Builder maxPages(int v) {
            this.maxPages = v;
            return this;
        }

        public Builder inMemoryRowCeiling(int v) {
            this.inMemoryRowCeiling = v;
            return this;
        }

        /**
         * <b>Not implemented.</b> design 04 specifies it as half of the `PLAN-007` memory guard; only the row ceiling is enforced.
         *
         * <p>Setting it to anything but the default is refused rather than silently ignored. A knob
         * that reads as a guarantee and does nothing is worse than a missing one — that is finding 16,
         * where a documented denial-of-service mitigation turned out to be documentation only.
         *
         * @throws UnsupportedOperationException for any value other than {@link #DEFAULT_IN_MEMORY_BYTE_CEILING}
         */
        public Builder inMemoryByteCeiling(int v) {
            if (v != DEFAULT_IN_MEMORY_BYTE_CEILING) {
                throw new UnsupportedOperationException(
                        "inMemoryByteCeiling is declared but not yet enforced, so setting it would imply a "
                                + "guarantee that does not exist. Leave it at the default ("
                                + DEFAULT_IN_MEMORY_BYTE_CEILING + ") until the planner consults it. See finding 16.");
            }
            this.inMemoryByteCeiling = v;
            return this;
        }

        public Builder estimatedVersionsPerKey(int v) {
            this.estimatedVersionsPerKey = v;
            return this;
        }

        public Builder allowGsi(boolean v) {
            this.allowGsi = v;
            return this;
        }

        public Builder inTransaction(boolean v) {
            this.inTransaction = v;
            return this;
        }

        public Builder allowScanDelete(boolean v) {
            this.allowScanDelete = v;
            return this;
        }

        public Builder allowFullCacheLoad(boolean v) {
            this.allowFullCacheLoad = v;
            return this;
        }

        /**
         * Items evaluated per DynamoDB Query/Scan request. Used as {@code Limit} when the plan has
         * no rowcount-based {@code dynamoLimit}, so {@link #maxPages()} is a reachable bound.
         */
        public Builder pageSize(int v) {
            if (v < 1) {
                throw new IllegalArgumentException("pageSize must be >= 1, got " + v);
            }
            this.pageSize = v;
            return this;
        }

        public Builder avgItemBytes(int v) {
            this.avgItemBytes = v;
            return this;
        }

        public PlannerConfig build() {
            return new PlannerConfig(this);
        }
    }
}
