package io.reladynamo.core.plan;

/**
 * DynamoDB access method chosen by the planner. {@code QUERY_FAN_OUT} is N Queries/GetItems
 * for an OR/IN of partition keys; it is never silently rewritten as {@link #SCAN}.
 */
public enum PlanKind {
    GET_ITEM,
    QUERY,
    QUERY_FAN_OUT,
    SCAN,
    EMPTY
}
