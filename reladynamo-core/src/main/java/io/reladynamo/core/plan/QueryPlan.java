package io.reladynamo.core.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Static plan produced by {@link QueryPlanner}. Pure data: no AWS types, no I/O.
 */
public final class QueryPlan {

    public static final String FAST_PATH_NONE = "NONE";
    public static final String FAST_PATH_CURRENT_ASOF = "CURRENT_ASOF";
    public static final String FAST_PATH_POINT_GET = "POINT_GET";

    private final String className;
    private final String tableName;
    private final String indexName;
    private final PlanKind kind;
    private final KeyCondition keyCondition;
    private final FilterExpression filterExpression;
    private final ResidualPredicate residual;
    private final Map<String, String> expressionAttributeNames;
    private final Map<String, ExpressionValue> expressionAttributeValues;
    private final boolean consistentRead;
    private final boolean scanIndexForward;
    private final Integer dynamoLimit;
    private final int estimatedItemsExamined;
    private final int avgItemBytes;
    private final int estimatedItemsReturned;
    private final OrderMode orderMode;
    private final Comparator<Map<String, Object>> rowComparator;
    private final int inMemoryRowCeiling;
    private final List<QueryPlan> fanOut;
    private final int segmentCount;
    private final int maxPages;
    private final int pageSize;
    private final String fastPath;
    private final int rowcount;
    private final PlanningPurpose purpose;

    private QueryPlan(Builder b) {
        this.className = Objects.requireNonNull(b.className, "className");
        this.tableName = Objects.requireNonNull(b.tableName, "tableName");
        this.indexName = b.indexName == null ? PhysicalDesign.PRIMARY_INDEX : b.indexName;
        this.kind = Objects.requireNonNull(b.kind, "kind");
        this.keyCondition = b.keyCondition;
        this.filterExpression = b.filterExpression == null ? FilterExpression.empty() : b.filterExpression;
        this.residual = b.residual == null ? ResidualPredicate.empty() : b.residual;
        Map<String, String> names = new LinkedHashMap<String, String>();
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        if (this.keyCondition != null) {
            names.putAll(this.keyCondition.names());
            values.putAll(this.keyCondition.values());
        }
        if (!this.filterExpression.isEmpty()) {
            names.putAll(this.filterExpression.names());
            values.putAll(this.filterExpression.values());
        }
        this.expressionAttributeNames = Collections.unmodifiableMap(names);
        this.expressionAttributeValues = Collections.unmodifiableMap(values);
        this.consistentRead = b.consistentRead;
        this.scanIndexForward = b.scanIndexForward;
        this.dynamoLimit = b.dynamoLimit;
        this.estimatedItemsExamined = b.estimatedItemsExamined;
        this.avgItemBytes = b.avgItemBytes;
        this.estimatedItemsReturned = b.estimatedItemsReturned;
        this.orderMode = b.orderMode == null ? OrderMode.NONE : b.orderMode;
        this.rowComparator = b.rowComparator;
        this.inMemoryRowCeiling = b.inMemoryRowCeiling;
        this.fanOut = b.fanOut == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<QueryPlan>(b.fanOut));
        this.segmentCount = b.segmentCount;
        this.maxPages = b.maxPages;
        this.pageSize = b.pageSize;
        this.fastPath = b.fastPath == null ? FAST_PATH_NONE : b.fastPath;
        this.rowcount = b.rowcount;
        this.purpose = b.purpose == null ? PlanningPurpose.FIND : b.purpose;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .className(className)
                .tableName(tableName)
                .indexName(indexName)
                .kind(kind)
                .keyCondition(keyCondition)
                .filterExpression(filterExpression)
                .residual(residual)
                .consistentRead(consistentRead)
                .scanIndexForward(scanIndexForward)
                .dynamoLimit(dynamoLimit)
                .estimatedItemsExamined(estimatedItemsExamined)
                .avgItemBytes(avgItemBytes)
                .estimatedItemsReturned(estimatedItemsReturned)
                .orderMode(orderMode)
                .rowComparator(rowComparator)
                .inMemoryRowCeiling(inMemoryRowCeiling)
                .fanOut(fanOut.isEmpty() ? null : new ArrayList<QueryPlan>(fanOut))
                .segmentCount(segmentCount)
                .maxPages(maxPages)
                .pageSize(pageSize)
                .fastPath(fastPath)
                .rowcount(rowcount)
                .purpose(purpose);
    }

    public String className() {
        return className;
    }

    public String tableName() {
        return tableName;
    }

    public String indexName() {
        return indexName;
    }

    public PlanKind kind() {
        return kind;
    }

    /** Design alias for {@link #kind()}. */
    public PlanKind accessMethod() {
        return kind;
    }

    public KeyCondition keyCondition() {
        return keyCondition;
    }

    public String keyConditionExpression() {
        return keyCondition == null ? null : keyCondition.expression();
    }

    public FilterExpression filter() {
        return filterExpression;
    }

    public String filterExpression() {
        return filterExpression.isEmpty() ? null : filterExpression.expression();
    }

    public ResidualPredicate residual() {
        return residual;
    }

    public String residualOperationDump() {
        return residual.dump();
    }

    public Map<String, String> expressionAttributeNames() {
        return expressionAttributeNames;
    }

    public Map<String, ExpressionValue> expressionAttributeValues() {
        return expressionAttributeValues;
    }

    public boolean consistentRead() {
        return consistentRead;
    }

    public boolean scanIndexForward() {
        return scanIndexForward;
    }

    public Integer dynamoLimit() {
        return dynamoLimit;
    }

    public int estimatedItemsExamined() {
        return estimatedItemsExamined;
    }

    /** Average stored item size used to turn an item count into a byte and read-unit estimate. */
    public int avgItemBytes() {
        return avgItemBytes;
    }

    /** {@link #estimatedItemsExamined()} x {@link #avgItemBytes()}. */
    public long estimatedBytesExamined() {
        return (long) estimatedItemsExamined * (long) avgItemBytes;
    }

    /**
     * Estimated read capacity units, per DynamoDB's read-capacity rules: reads are billed in whole
     * <b>4 KB</b> blocks, a strongly consistent read costs one unit per block and an eventually
     * consistent read costs half, and Query/Scan are charged on the bytes <em>examined</em> rather
     * than returned - which is why this is built from {@code estimatedItemsExamined}.
     *
     * <p>An estimate, not a bill: it uses one configured average item size rather than real item
     * sizes, and it does not model a GSI's own projected size. Its purpose is to make two access
     * paths comparable at plan time.
     */
    public double estimatedRcu() {
        long bytes = estimatedBytesExamined();
        if (bytes <= 0L) {
            return 0.0d;
        }
        long blocks = (bytes + 4095L) / 4096L;
        return consistentRead ? (double) blocks : blocks / 2.0d;
    }

    public int estimatedItemsReturned() {
        return estimatedItemsReturned;
    }

    public OrderMode orderMode() {
        return orderMode;
    }

    /**
     * Comparator for decoded rows, derived from Reladomo {@code OrderBy} (never from
     * {@code OrderBy.toString()}). Null when the plan has no in-memory order.
     */
    public Comparator<Map<String, Object>> rowComparator() {
        return rowComparator;
    }

    /**
     * Maximum decoded rows the executor may accumulate to satisfy in-memory order.
     * {@code 0} means unset (no execute-time cap on this plan).
     */
    public int inMemoryRowCeiling() {
        return inMemoryRowCeiling;
    }

    public List<QueryPlan> fanOut() {
        return fanOut;
    }

    public int segmentCount() {
        return segmentCount;
    }

    /**
     * Maximum pages the executor may follow, or {@code 0} for unbounded.
     *
     * <p>Exceeding it is an error, not a truncation: silently returning the first N pages of a larger
     * result is the worst available outcome — the caller cannot tell a complete answer from a clipped
     * one.
     */
    public int maxPages() {
        return maxPages;
    }

    /**
     * DynamoDB request page size ({@code Limit}) so {@link #maxPages()} is enforceable.
     * {@code 0} means unset (the executor does not inject a Limit from this field).
     */
    public int pageSize() {
        return pageSize;
    }

    public String fastPath() {
        return fastPath;
    }

    public int rowcount() {
        return rowcount;
    }

    public PlanningPurpose purpose() {
        return purpose;
    }

    public boolean hasFilterOrResidual() {
        return !filterExpression.isEmpty() || !residual.isEmpty();
    }

    public ExplainPlan explain() {
        return new ExplainPlan(this);
    }

    public String toAssertableString() {
        return "QueryPlan{class=" + className
                + ", index=" + indexName
                + ", method=" + kind
                + ", key=" + keyConditionExpression()
                + ", filter=" + filterExpression()
                + ", residual=" + residualOperationDump()
                + ", examined~=" + estimatedItemsExamined
                + ", rcu~=" + estimatedRcu()
                + ", returned~=" + estimatedItemsReturned
                + ", fastPath=" + fastPath
                + "}";
    }

    @Override
    public String toString() {
        return toAssertableString();
    }

    public static final class Builder {
        private String className;
        private String tableName;
        private String indexName = PhysicalDesign.PRIMARY_INDEX;
        private PlanKind kind;
        private KeyCondition keyCondition;
        private FilterExpression filterExpression;
        private ResidualPredicate residual;
        private boolean consistentRead = true;
        private boolean scanIndexForward = true;
        private Integer dynamoLimit;
        private int estimatedItemsExamined;
        private int avgItemBytes = PlannerConfig.DEFAULT_AVG_ITEM_BYTES;
        private int estimatedItemsReturned;
        private OrderMode orderMode = OrderMode.NONE;
        private Comparator<Map<String, Object>> rowComparator;
        private int inMemoryRowCeiling;
        private List<QueryPlan> fanOut;
        private int maxPages;
        private int pageSize;
        private int segmentCount;
        private String fastPath = FAST_PATH_NONE;
        private int rowcount;
        private PlanningPurpose purpose = PlanningPurpose.FIND;

        public Builder className(String v) {
            this.className = v;
            return this;
        }

        public Builder tableName(String v) {
            this.tableName = v;
            return this;
        }

        public Builder indexName(String v) {
            this.indexName = v;
            return this;
        }

        public Builder kind(PlanKind v) {
            this.kind = v;
            return this;
        }

        public Builder keyCondition(KeyCondition v) {
            this.keyCondition = v;
            return this;
        }

        public Builder filterExpression(FilterExpression v) {
            this.filterExpression = v;
            return this;
        }

        public Builder residual(ResidualPredicate v) {
            this.residual = v;
            return this;
        }

        public Builder consistentRead(boolean v) {
            this.consistentRead = v;
            return this;
        }

        public Builder scanIndexForward(boolean v) {
            this.scanIndexForward = v;
            return this;
        }

        public Builder dynamoLimit(Integer v) {
            this.dynamoLimit = v;
            return this;
        }

        public Builder avgItemBytes(int v) {
            this.avgItemBytes = v;
            return this;
        }

        public Builder estimatedItemsExamined(int v) {
            this.estimatedItemsExamined = v;
            return this;
        }

        public Builder estimatedItemsReturned(int v) {
            this.estimatedItemsReturned = v;
            return this;
        }

        public Builder orderMode(OrderMode v) {
            this.orderMode = v;
            return this;
        }

        public Builder rowComparator(Comparator<Map<String, Object>> v) {
            this.rowComparator = v;
            return this;
        }

        public Builder inMemoryRowCeiling(int v) {
            this.inMemoryRowCeiling = v;
            return this;
        }

        public Builder fanOut(List<QueryPlan> v) {
            this.fanOut = v;
            return this;
        }

        public Builder segmentCount(int v) {
            this.segmentCount = v;
            return this;
        }

        public Builder maxPages(int v) {
            this.maxPages = v;
            return this;
        }

        public Builder pageSize(int v) {
            this.pageSize = v;
            return this;
        }

        public Builder fastPath(String v) {
            this.fastPath = v;
            return this;
        }

        public Builder rowcount(int v) {
            this.rowcount = v;
            return this;
        }

        public Builder purpose(PlanningPurpose v) {
            this.purpose = v;
            return this;
        }

        public QueryPlan build() {
            return new QueryPlan(this);
        }
    }
}
