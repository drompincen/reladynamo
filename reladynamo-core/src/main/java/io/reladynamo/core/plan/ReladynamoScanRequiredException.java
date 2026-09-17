package io.reladynamo.core.plan;

/**
 * A Scan would be required and scans are not opted in. Exact message contract is PLAN-001.
 */
public class ReladynamoScanRequiredException extends ReladynamoUnplannableOperationException {

    public ReladynamoScanRequiredException(String message) {
        super(message);
    }

    public static ReladynamoScanRequiredException plan001(String className, String tableName, Object operation) {
        return new ReladynamoScanRequiredException(
                "RELADYNAMO-PLAN-001: Scan required for " + className + " on table " + tableName
                        + " but scans are disabled.\n"
                        + "The operation does not bound a partition key on the base table or any GSI.\n"
                        + "Enable scans only for bounded batch jobs: ReladynamoConfig queryPlanner.allowTableScan(true)\n"
                        + "(or reladynamo.xml <QueryPlanner allowTableScan=\"true\"/>).\n"
                        + "Operation: " + io.reladynamo.core.plan.reladomo.ReladomoOperationAccess.dump(operation));
    }
}
