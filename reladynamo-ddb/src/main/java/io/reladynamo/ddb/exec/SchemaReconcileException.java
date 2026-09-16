package io.reladynamo.ddb.exec;

/**
 * The existing table cannot be used as requested. Never a silent no-op: callers must see
 * {@link #code()} and {@link #result()}.
 */
public final class SchemaReconcileException extends IllegalStateException {

    public static final String CODE_INCOMPATIBLE = "RD_TABLE_INCOMPATIBLE";
    public static final String CODE_BACKFILL_INDEX_KEY = "RD_TABLE_BACKFILL_INDEX_KEY";

    private final SchemaReconcileResult result;

    public SchemaReconcileException(SchemaReconcileResult result) {
        super(messageFor(result));
        this.result = result;
    }

    public SchemaReconcileResult result() {
        return result;
    }

    public SchemaReconcileOutcome outcome() {
        return result.outcome();
    }

    public String code() {
        if (result.outcome() == SchemaReconcileOutcome.BACKFILL_INDEX_KEY) {
            return CODE_BACKFILL_INDEX_KEY;
        }
        return CODE_INCOMPATIBLE;
    }

    private static String messageFor(SchemaReconcileResult result) {
        if (result == null) {
            return "table design is incompatible";
        }
        String code = result.outcome() == SchemaReconcileOutcome.BACKFILL_INDEX_KEY
                ? CODE_BACKFILL_INDEX_KEY
                : CODE_INCOMPATIBLE;
        return code + ": table '" + result.tableName() + "' " + result.detail();
    }
}
