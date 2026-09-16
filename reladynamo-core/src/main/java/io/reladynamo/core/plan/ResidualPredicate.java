package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.Operation;

/**
 * Leftover Reladomo predicate evaluated in memory via {@link Operation#matches(Object)}.
 *
 * <p>{@code matches} returns a nullable {@code Boolean}: {@code null} means "cannot determine".
 * For filtering, {@code null} is <b>not a pass</b>. If a {@code null} reaches this residual for a
 * predicate the planner claimed it could evaluate in memory, that is a planner bug — we fail
 * loudly rather than silently drop rows.
 */
public final class ResidualPredicate {

    private final Operation operation;

    public ResidualPredicate(Operation operation) {
        this.operation = operation;
    }

    public static ResidualPredicate empty() {
        return new ResidualPredicate(null);
    }

    public Operation operation() {
        return operation;
    }

    public boolean isEmpty() {
        return operation == null;
    }

    /**
     * Strict in-memory evaluation. {@code null} from Reladomo is treated as a planner bug.
     */
    public boolean matchesOrThrow(Object candidate) {
        if (operation == null) {
            return true;
        }
        Boolean result = operation.matches(candidate);
        if (result == null) {
            throw new ReladynamoResidualEvaluationException(
                    "planner claimed residual was evaluable in memory but Operation.matches() returned null "
                            + "(cannot determine). This is a planner mis-classification, not a filter miss. "
                            + "Operation: " + operation);
        }
        return result.booleanValue();
    }

    /**
     * Filter semantics: {@code null} is not a pass. Does not throw — used when the caller has not
     * yet claimed the predicate is fully in-memory evaluable (e.g. adversarial oracle).
     */
    public static boolean isPass(Boolean matchesResult) {
        return Boolean.TRUE.equals(matchesResult);
    }

    public String dump() {
        if (operation == null) {
            return "";
        }
        try {
            return operation.toString();
        } catch (RuntimeException e) {
            return operation.getClass().getName();
        }
    }

    @Override
    public String toString() {
        return dump();
    }
}
