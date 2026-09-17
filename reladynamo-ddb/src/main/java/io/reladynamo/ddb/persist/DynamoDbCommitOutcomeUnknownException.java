package io.reladynamo.ddb.persist;

import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/** Preserve the exact request for incident reconciliation; never mistake this for a rollback. */
public final class DynamoDbCommitOutcomeUnknownException extends DynamoDbTransactionException {

    private final TransactWriteItemsRequest request;
    private final long firstAttemptNanos;

    DynamoDbCommitOutcomeUnknownException(TransactWriteItemsRequest request, long firstAttemptNanos,
                                          Throwable cause) {
        super("RELADYNAMO-TXN-003",
                "UNKNOWN commit outcome; client quarantined. Do not replay the logical command. ClientRequestToken="
                        + request.clientRequestToken(),
                cause);
        this.request = request;
        this.firstAttemptNanos = firstAttemptNanos;
    }

    public TransactWriteItemsRequest request() {
        return request;
    }

    long firstAttemptNanos() {
        return firstAttemptNanos;
    }
}
