package io.reladynamo.ddb.write;

import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;

/**
 * The one DynamoDB call {@link BatchWriter} needs.
 *
 * <p>Narrowing the dependency to this keeps the retry contract testable with a scripted fake rather
 * than a live endpoint — the partial-failure paths are the ones that matter and are awkward to
 * provoke against a real service.
 */
public interface BatchWriteClient {
    BatchWriteItemResponse batchWriteItem(BatchWriteItemRequest request);
}
