package io.reladynamo.ddb.exec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Explicit outcome of reconciling a requested physical design against DynamoDB.
 */
public final class SchemaReconcileResult {

    private final SchemaReconcileOutcome outcome;
    private final String tableName;
    private final String detail;
    private final List<String> missingIndexNames;

    public SchemaReconcileResult(SchemaReconcileOutcome outcome, String tableName, String detail,
                                 List<String> missingIndexNames) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        this.detail = detail == null ? "" : detail;
        this.missingIndexNames = missingIndexNames == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(missingIndexNames));
    }

    public static SchemaReconcileResult of(SchemaReconcileOutcome outcome, String tableName, String detail) {
        return new SchemaReconcileResult(outcome, tableName, detail, Collections.emptyList());
    }

    public SchemaReconcileOutcome outcome() {
        return outcome;
    }

    public String tableName() {
        return tableName;
    }

    public String detail() {
        return detail;
    }

    public List<String> missingIndexNames() {
        return missingIndexNames;
    }

    public boolean isRefused() {
        return outcome == SchemaReconcileOutcome.INCOMPATIBLE
                || outcome == SchemaReconcileOutcome.BACKFILL_INDEX_KEY;
    }
}
