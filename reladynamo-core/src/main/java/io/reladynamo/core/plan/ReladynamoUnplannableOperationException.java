package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.MithraBusinessException;

/**
 * The operation cannot be answered by a DynamoDB read that preserves Reladomo's row set.
 * Never retried: the plan is wrong, not flaky.
 */
public class ReladynamoUnplannableOperationException extends MithraBusinessException {

    public ReladynamoUnplannableOperationException(String message) {
        super(message);
        setRetriable(false);
    }

    public ReladynamoUnplannableOperationException(String message, Throwable cause) {
        super(message, cause);
        setRetriable(false);
    }
}
