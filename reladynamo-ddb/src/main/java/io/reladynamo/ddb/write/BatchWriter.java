package io.reladynamo.ddb.write;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Writes a batch of {@link WriteRequest}s, honouring the two things that make {@code BatchWriteItem}
 * dangerous:
 *
 * <ol>
 *   <li><b>The 25-item hard limit.</b> Larger requests are rejected outright.
 *   <li><b>{@code UnprocessedItems} on a 200 response.</b> A successful HTTP status does not mean the
 *       batch was written. Items throttled or rejected individually come back for resubmission, and
 *       code that ignores that field loses writes with no error at all — the failure is discovered
 *       later as missing rows, with nothing in the logs to explain them.
 * </ol>
 *
 * <p>When the attempt budget is exhausted with items still unprocessed, this throws rather than
 * returning. Returning normally would be the silent data loss it exists to prevent.
 *
 * <p>Java 11 baseline.
 */
public final class BatchWriter {

    /** DynamoDB's hard limit on writes per BatchWriteItem call. */
    public static final int MAX_BATCH = 25;

    private final BatchWriteClient client;
    private final String tableName;
    private final int maxAttempts;
    private final long baseBackoffMillis;

    public BatchWriter(BatchWriteClient client, String tableName, int maxAttempts,
                       long baseBackoffMillis) {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.client = client;
        this.tableName = tableName;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMillis = baseBackoffMillis;
    }

    public void writeAll(List<WriteRequest> writes) {
        if (writes == null || writes.isEmpty()) {
            return;
        }
        rejectDuplicateKeys(writes);
        for (int from = 0; from < writes.size(); from += MAX_BATCH) {
            int to = Math.min(from + MAX_BATCH, writes.size());
            writeChunk(new ArrayList<>(writes.subList(from, to)));
        }
    }

    private void writeChunk(List<WriteRequest> chunk) {
        List<WriteRequest> pending = chunk;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Map<String, List<WriteRequest>> items = new HashMap<>();
            items.put(tableName, pending);
            BatchWriteItemResponse response = client.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(items).build());

            List<WriteRequest> unprocessed = unprocessed(response);
            if (unprocessed.isEmpty()) {
                return;
            }
            pending = unprocessed;
            if (attempt < maxAttempts) {
                backoff(attempt);
            }
        }
        throw new UnprocessedWritesException(
                "BatchWriteItem left " + pending.size() + " item(s) unprocessed on table '"
                        + tableName + "' after " + maxAttempts + " attempt(s). These writes were NOT "
                        + "persisted; failing rather than returning, because a silent partial write "
                        + "surfaces later as missing rows with nothing to explain them.",
                pending.size());
    }

    private List<WriteRequest> unprocessed(BatchWriteItemResponse response) {
        if (response == null || !response.hasUnprocessedItems()) {
            return new ArrayList<>();
        }
        List<WriteRequest> forTable = response.unprocessedItems().get(tableName);
        return forTable == null ? new ArrayList<>() : new ArrayList<>(forTable);
    }

    /**
     * DynamoDB rejects a batch containing two operations on the same key. Detecting it here names the
     * offending key; leaving it to AWS yields a ValidationException that does not.
     */
    private void rejectDuplicateKeys(List<WriteRequest> writes) {
        Set<String> seen = new HashSet<>();
        for (WriteRequest w : writes) {
            Map<String, AttributeValue> key = w.putRequest() != null
                    ? w.putRequest().item()
                    : w.deleteRequest() != null ? w.deleteRequest().key() : null;
            if (key == null) {
                continue;
            }
            String id = keyOf(key);
            if (!seen.add(id)) {
                throw new IllegalArgumentException(
                        "two operations on the same item key in one batch: " + id
                                + " — DynamoDB rejects the whole request, so split them across batches");
            }
        }
    }

    private static String keyOf(Map<String, AttributeValue> item) {
        AttributeValue pk = item.get("pk");
        AttributeValue sk = item.get("sk");
        StringBuilder sb = new StringBuilder();
        sb.append(pk == null ? "?" : pk.s());
        if (sk != null) {
            sb.append('/').append(sk.s());
        }
        return sb.toString();
    }

    /** Exponential backoff with full jitter; jitter matters because a throttled batch is rarely alone. */
    private void backoff(int attempt) {
        if (baseBackoffMillis <= 0L) {
            return;
        }
        long ceiling = baseBackoffMillis * (1L << Math.min(attempt - 1, 10));
        long sleep = ThreadLocalRandom.current().nextLong(ceiling + 1);
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off a batch write retry", e);
        }
    }
}
