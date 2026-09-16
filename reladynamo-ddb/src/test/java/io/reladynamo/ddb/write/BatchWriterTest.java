package io.reladynamo.ddb.write;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code BatchWriteItem} can return HTTP 200 having written only part of the batch, with the rest in
 * {@code UnprocessedItems}. Ignoring that field loses writes silently — no exception, no log, just
 * missing rows discovered much later. These tests pin the retry contract.
 */
class BatchWriterTest {

    @Test
    void splits_into_chunks_of_at_most_25_because_that_is_the_hard_api_limit() {
        RecordingClient client = new RecordingClient(req -> BatchWriteItemResponse.builder().build());
        List<WriteRequest> writes = puts(60);

        new BatchWriter(client, "t", 5, 0L).writeAll(writes);

        assertThat(client.batchSizes).containsExactly(25, 25, 10);
    }

    @Test
    void resubmits_unprocessed_items_until_empty() {
        // First call leaves 2 unprocessed, second leaves 1, third clears them.
        final int[] call = {0};
        RecordingClient client = new RecordingClient(req -> {
            call[0]++;
            List<WriteRequest> sent = req.requestItems().get("t");
            int leave = call[0] == 1 ? 2 : call[0] == 2 ? 1 : 0;
            if (leave == 0) {
                return BatchWriteItemResponse.builder().build();
            }
            Map<String, List<WriteRequest>> un = new HashMap<>();
            un.put("t", new ArrayList<>(sent.subList(0, leave)));
            return BatchWriteItemResponse.builder().unprocessedItems(un).build();
        });

        new BatchWriter(client, "t", 5, 0L).writeAll(puts(3));

        assertThat(call[0]).as("must keep resubmitting until UnprocessedItems is empty").isEqualTo(3);
    }

    @Test
    void gives_up_loudly_rather_than_silently_dropping_writes() {
        // A batch that never drains must fail. Returning normally here would mean lost data.
        RecordingClient client = new RecordingClient(req -> {
            Map<String, List<WriteRequest>> un = new HashMap<>();
            un.put("t", new ArrayList<>(req.requestItems().get("t")));
            return BatchWriteItemResponse.builder().unprocessedItems(un).build();
        });

        assertThatThrownBy(() -> new BatchWriter(client, "t", 3, 0L).writeAll(puts(2)))
                .isInstanceOf(UnprocessedWritesException.class)
                .hasMessageContaining("2")
                .hasMessageContaining("3");
    }

    @Test
    void an_empty_batch_issues_no_call_at_all() {
        RecordingClient client = new RecordingClient(req -> BatchWriteItemResponse.builder().build());
        new BatchWriter(client, "t", 5, 0L).writeAll(new ArrayList<>());
        assertThat(client.batchSizes).isEmpty();
    }

    @Test
    void rejects_a_batch_containing_two_operations_on_the_same_key() {
        // DynamoDB rejects duplicate keys within one BatchWriteItem. Catching it here names the
        // offending key; letting AWS reject it yields an opaque ValidationException.
        RecordingClient client = new RecordingClient(req -> BatchWriteItemResponse.builder().build());
        List<WriteRequest> dupes = new ArrayList<>();
        dupes.add(put("same", "sk"));
        dupes.add(put("same", "sk"));
        assertThatThrownBy(() -> new BatchWriter(client, "t", 5, 0L).writeAll(dupes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same");
    }

    // --- helpers ------------------------------------------------------------------------
    private static List<WriteRequest> puts(int n) {
        List<WriteRequest> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(put("pk" + i, "sk" + i));
        }
        return l;
    }

    private static WriteRequest put(String pk, String sk) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("pk", AttributeValue.builder().s(pk).build());
        item.put("sk", AttributeValue.builder().s(sk).build());
        return WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build();
    }

    /** Minimal fake: records batch sizes and returns whatever the scripted function says. */
    private static final class RecordingClient implements BatchWriteClient {
        private final Function<BatchWriteItemRequest, BatchWriteItemResponse> script;
        final List<Integer> batchSizes = new ArrayList<>();

        RecordingClient(Function<BatchWriteItemRequest, BatchWriteItemResponse> script) {
            this.script = script;
        }

        @Override
        public BatchWriteItemResponse batchWriteItem(BatchWriteItemRequest request) {
            batchSizes.add(request.requestItems().get("t").size());
            return script.apply(request);
        }
    }
}
