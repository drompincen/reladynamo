package io.reladynamo.core.plan;

/**
 * Static explain of a {@link QueryPlan}. Runtime actuals (ScannedCount, RCU) are filled by the
 * executor, not the planner.
 */
public final class ExplainPlan {

    private final QueryPlan plan;

    public ExplainPlan(QueryPlan plan) {
        this.plan = plan;
    }

    public QueryPlan plan() {
        return plan;
    }

    public PlanKind kind() {
        return plan.kind();
    }

    public String indexName() {
        return plan.indexName();
    }

    public String fastPath() {
        return plan.fastPath();
    }

    public int estimatedItemsExamined() {
        return plan.estimatedItemsExamined();
    }

    public int estimatedItemsReturned() {
        return plan.estimatedItemsReturned();
    }

    public String keyConditionExpression() {
        return plan.keyConditionExpression();
    }

    public String filterExpression() {
        return plan.filterExpression();
    }

    public Integer dynamoLimit() {
        return plan.dynamoLimit();
    }

    @Override
    public String toString() {
        return plan.toAssertableString();
    }
}
