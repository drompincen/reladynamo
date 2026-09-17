package io.reladynamo.ddb.exec;

import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;

/**
 * Runtime snapshot of what {@link QueryPlanExecutor} actually did. The planner's
 * {@link io.reladynamo.core.plan.ExplainPlan} is static; this holds ScannedCount, RCU, pages.
 */
public final class ExecutionExplain {

    private final QueryPlan plan;
    private final PlanKind kind;
    private final String indexName;
    private final int actualItemsExamined;
    private final int dynamoItemsReturned;
    private final int actualItemsReturned;
    private final double consumedCapacityRcu;
    private final int pageCount;
    private final int requestCount;
    private final long durationMs;

    public ExecutionExplain(QueryPlan plan, PlanKind kind, String indexName,
                            int actualItemsExamined, int dynamoItemsReturned, int actualItemsReturned,
                            double consumedCapacityRcu, int pageCount, int requestCount, long durationMs) {
        this.plan = plan;
        this.kind = kind;
        this.indexName = indexName;
        this.actualItemsExamined = actualItemsExamined;
        this.dynamoItemsReturned = dynamoItemsReturned;
        this.actualItemsReturned = actualItemsReturned;
        this.consumedCapacityRcu = consumedCapacityRcu;
        this.pageCount = pageCount;
        this.requestCount = requestCount;
        this.durationMs = durationMs;
    }

    public QueryPlan plan() {
        return plan;
    }

    public PlanKind kind() {
        return kind;
    }

    public String indexName() {
        return indexName;
    }

    public int actualItemsExamined() {
        return actualItemsExamined;
    }

    /** {@code Count} from DynamoDB, before residual filtering. */
    public int dynamoItemsReturned() {
        return dynamoItemsReturned;
    }

    /** Rows returned to the caller after residual. */
    public int actualItemsReturned() {
        return actualItemsReturned;
    }

    public double consumedCapacityRcu() {
        return consumedCapacityRcu;
    }

    public int pageCount() {
        return pageCount;
    }

    public int requestCount() {
        return requestCount;
    }

    public long durationMs() {
        return durationMs;
    }
}
