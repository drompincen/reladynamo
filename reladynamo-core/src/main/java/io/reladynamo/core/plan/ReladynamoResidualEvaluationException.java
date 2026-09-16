package io.reladynamo.core.plan;

/**
 * A residual predicate the planner claimed was evaluable in memory returned
 * {@code Operation.matches() == null} ("cannot determine"). That is a planner
 * mis-classification, not a legitimate filter miss — fail loudly rather than drop rows.
 */
public class ReladynamoResidualEvaluationException extends ReladynamoUnplannableOperationException {

    public ReladynamoResidualEvaluationException(String message) {
        super(message);
    }

    public ReladynamoResidualEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
