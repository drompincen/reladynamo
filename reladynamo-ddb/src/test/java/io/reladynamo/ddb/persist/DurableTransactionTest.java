package io.reladynamo.ddb.persist;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.MithraUniqueIndexViolationException;
import com.gs.fw.common.mithra.TransactionalCommand;
import com.gs.fw.common.mithra.behavior.txparticipation.MithraOptimisticLockException;
import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.ddb.codec.ItemCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;

import javax.transaction.Synchronization;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Real Reladomo transactions; deterministic physical-store boundary. Local subclass uses same contract. */
public class DurableTransactionTest {
    protected final Store store = createStore();

    protected Store createStore() {
        return new Store();
    }

    @BeforeEach
    void resetStore() {
        DynamoDbTransactionCoordinator.forgetUnknownOutcome(store.client);
        store.reset();
    }

    @AfterEach
    void forgetUnknownClient() {
        DynamoDbTransactionCoordinator.forgetUnknownOutcome(store.client);
    }

    static EntityMapping mapping(String table) {
        return new EntityMapping("example.Row", table, TemporalMapping.none(), Arrays.asList(
                new AttributeMapping("id", "id", "int", true, false),
                new AttributeMapping("value", "value", "String", false, false)));
    }

    DynamoDbWriter writer(String table) {
        EntityMapping mapping = mapping(table);
        return new DynamoDbWriter(store.client, mapping, new ItemCodec(mapping), new DefaultKeyStrategy());
    }

    static Map<String, Object> row(int id, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("value", value);
        return row;
    }

    static void tx(TransactionalCommand<Object> command) {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(command);
    }

    static String messages(Throwable t) {
        StringBuilder s = new StringBuilder();
        while (t != null) {
            s.append(t.getMessage()).append('\n');
            t = t.getCause();
        }
        return s.toString();
    }

    static boolean isOrCausedBy(Throwable t, Class<? extends Throwable> type) {
        return findCause(t, type) != null;
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        while (t != null) {
            if (type.isInstance(t)) {
                return (T) t;
            }
            t = t.getCause();
        }
        return null;
    }

    @Test
    void flush_then_throw_leaves_nothing_durable() {
        DynamoDbWriter writer = writer("txn_first");
        assertThatThrownBy(() -> tx(t -> {
            writer.insert(row(1, "new"));
            t.executeBufferedOperations();
            throw new IllegalStateException("abort");
        })).hasMessageContaining("abort");
        assertThat(store.rows("txn_first")).isEmpty();
    }

    @Test
    void failure_between_physical_close_and_replacement_keeps_old_row_open() {
        DynamoDbWriter writer = writer("txn_first");
        writer.insert(row(1, "open"));
        assertThatThrownBy(() -> tx(t -> {
            writer.update(row(1, "closed"), row(1, "open"));
            t.executeBufferedOperations();
            throw new IllegalStateException("before replacement");
        })).hasMessageContaining("before replacement");
        assertThat(store.rows("txn_first")).singleElement()
                .satisfies(r -> assertThat(r.get("value").s()).isEqualTo("open"));
    }

    @Test
    void two_tables_commit_together_and_are_invisible_until_commit() {
        tx(t -> {
            writer("txn_first").insert(row(1, "a"));
            writer("txn_second").insert(row(2, "b"));
            assertThat(store.rows("txn_first")).isEmpty();
            assertThat(store.rows("txn_second")).isEmpty();
            return null;
        });
        assertThat(store.rows("txn_first")).hasSize(1);
        assertThat(store.rows("txn_second")).hasSize(1);
        assertThat(store.requests).hasSize(1);
        assertThat(store.requests.get(0).transactItems()).hasSize(2);
    }

    @Test
    void two_tables_abort_together() {
        assertThatThrownBy(() -> tx(t -> {
            writer("txn_first").insert(row(1, "a"));
            writer("txn_second").insert(row(2, "b"));
            throw new IllegalStateException("abort");
        }));
        assertThat(store.rows("txn_first")).isEmpty();
        assertThat(store.rows("txn_second")).isEmpty();
    }

    @Test
    void over_100_actions_refused_before_any_mutation() {
        Throwable error = catchThrowable(() -> tx(t -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                rows.add(row(i, "v"));
            }
            writer("txn_first").batchInsert(rows);
            return null;
        }));
        assertThat(messages(error)).contains("RELADYNAMO-TXN-001");
        assertThat(store.rows("txn_first")).isEmpty();
        assertThat(store.requests).isEmpty();
    }

    @Test
    void over_four_megabytes_refused_before_any_mutation() {
        Throwable error = catchThrowable(() -> tx(t -> {
            DynamoDbWriter writer = writer("txn_first");
            for (int i = 0; i < 15; i++) {
                writer.insert(row(i, "x".repeat(300_000)));
            }
            return null;
        }));
        assertThat(messages(error)).contains("RELADYNAMO-TXN-002");
        assertThat(store.rows("txn_first")).isEmpty();
        assertThat(store.requests).isEmpty();
    }

    @Test
    void repeated_item_actions_coalesce_to_the_final_image() {
        tx(t -> {
            DynamoDbWriter writer = writer("txn_first");
            writer.insert(row(1, "first"));
            writer.delete(row(1, "first"));
            writer.insert(row(1, "last"));
            return null;
        });
        assertThat(store.requests).hasSize(1);
        assertThat(store.requests.get(0).transactItems()).hasSize(1);
        Put put = store.requests.get(0).transactItems().get(0).put();
        assertThat(put.item().get("value").s()).isEqualTo("last");
        assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(pk)");
        assertThat(store.rows("txn_first")).singleElement()
                .satisfies(r -> assertThat(r.get("value").s()).isEqualTo("last"));
    }

    @Test
    void transactional_insert_carries_r02_not_exists_condition() {
        tx(t -> {
            writer("txn_first").insert(row(1, "a"));
            return null;
        });
        assertThat(store.requests).hasSize(1);
        Put put = store.requests.get(0).transactItems().get(0).put();
        assertThat(put.conditionExpression()).isEqualTo("attribute_not_exists(pk)");
    }

    @Test
    void transactional_update_carries_r02_expected_state_condition() {
        DynamoDbWriter writer = writer("txn_first");
        writer.insert(row(1, "old"));
        tx(t -> {
            writer.update(row(1, "new"), row(1, "old"));
            return null;
        });
        assertThat(store.requests).hasSize(1);
        Put put = store.requests.get(0).transactItems().get(0).put();
        assertThat(put.conditionExpression()).contains("attribute_exists(pk)");
        assertThat(put.expressionAttributeValues()).isNotEmpty();
        assertThat(store.rows("txn_first")).singleElement()
                .satisfies(r -> assertThat(r.get("value").s()).isEqualTo("new"));
    }

    @Test
    void transactional_delete_carries_r02_expected_state_condition() {
        DynamoDbWriter writer = writer("txn_first");
        writer.insert(row(1, "old"));
        tx(t -> {
            writer.delete(row(1, "old"));
            return null;
        });
        assertThat(store.requests).hasSize(1);
        Delete del = store.requests.get(0).transactItems().get(0).delete();
        assertThat(del.conditionExpression()).contains("attribute_exists(pk)");
        assertThat(store.rows("txn_first")).isEmpty();
    }

    @Test
    void transactional_upsert_has_no_condition() {
        tx(t -> {
            writer("txn_first").upsert(row(1, "a"));
            return null;
        });
        Put put = store.requests.get(0).transactItems().get(0).put();
        assertThat(put.conditionExpression()).isNull();
    }

    @Test
    void timeout_after_server_commit_replays_identical_token_and_request() {
        store.failure = "timeoutAfter";
        tx(t -> {
            writer("txn_first").insert(row(1, "one"));
            return null;
        });
        assertThat(store.requests).hasSize(2);
        assertThat(store.requests.get(1)).isEqualTo(store.requests.get(0));
        assertThat(store.requests.get(0).clientRequestToken()).isNotBlank();
        assertThat(store.rows("txn_first")).hasSize(1);
    }

    @Test
    void retryable_cancellation_replays_identical_request() {
        store.failure = "conflict";
        tx(t -> {
            writer("txn_first").insert(row(1, "one"));
            return null;
        });
        assertThat(store.requests).hasSize(2);
        assertThat(store.requests.get(1)).isEqualTo(store.requests.get(0));
        assertThat(store.rows("txn_first")).hasSize(1);
    }

    @Test
    void transaction_in_progress_retries_with_same_token() {
        store.failure = "progress";
        tx(t -> {
            writer("txn_first").insert(row(1, "one"));
            return null;
        });
        assertThat(store.requests).hasSize(2);
        assertThat(store.requests.get(1)).isEqualTo(store.requests.get(0));
    }

    @Test
    void permanent_cancellation_aborts_both_tables_without_retry() {
        store.failure = "condition";
        Throwable error = catchThrowable(() -> tx(t -> {
            writer("txn_first").insert(row(1, "one"));
            writer("txn_second").insert(row(2, "two"));
            return null;
        }));
        assertThat(messages(error)).contains("RELADYNAMO-TXN-004");
        assertThat(store.requests).hasSize(1);
        assertThat(store.rows("txn_first")).isEmpty();
        assertThat(store.rows("txn_second")).isEmpty();
    }

    @Test
    void aligned_insert_condition_failure_is_unique_index_violation() {
        store.failure = "alignedCondition";
        Throwable error = catchThrowable(() -> tx(t -> {
            writer("txn_first").insert(row(1, "one"));
            return null;
        }));
        assertThat(isOrCausedBy(error, MithraUniqueIndexViolationException.class))
                .as("expected MithraUniqueIndexViolationException in the cause chain. actual=%s",
                        messages(error))
                .isTrue();
        assertThat(messages(error)).doesNotContain("RELADYNAMO-TXN-004");
        assertThat(store.requests).hasSize(1);
        assertThat(store.rows("txn_first")).isEmpty();
    }

    @Test
    void aligned_update_condition_failure_is_optimistic_lock() {
        DynamoDbWriter writer = writer("txn_first");
        writer.insert(row(1, "old"));
        store.failure = "alignedCondition";
        Throwable error = catchThrowable(() -> tx(t -> {
            writer.update(row(1, "new"), row(1, "old"));
            return null;
        }));
        assertThat(isOrCausedBy(error, MithraOptimisticLockException.class))
                .as("expected MithraOptimisticLockException in the cause chain. actual=%s",
                        messages(error))
                .isTrue();
        assertThat(findCause(error, MithraOptimisticLockException.class).isRetriable())
                .as("Reladomo retries only when isRetriable() is true")
                .isTrue();
        assertThat(messages(error)).doesNotContain("RELADYNAMO-TXN-004");
        assertThat(store.rows("txn_first")).singleElement()
                .satisfies(r -> assertThat(r.get("value").s()).isEqualTo("old"));
    }

    @Test
    void aligned_delete_condition_failure_is_optimistic_lock() {
        DynamoDbWriter writer = writer("txn_first");
        writer.insert(row(1, "old"));
        store.failure = "alignedCondition";
        Throwable error = catchThrowable(() -> tx(t -> {
            writer.delete(row(1, "old"));
            return null;
        }));
        assertThat(isOrCausedBy(error, MithraOptimisticLockException.class))
                .as("expected MithraOptimisticLockException in the cause chain. actual=%s",
                        messages(error))
                .isTrue();
        assertThat(findCause(error, MithraOptimisticLockException.class).isRetriable()).isTrue();
        assertThat(messages(error)).doesNotContain("RELADYNAMO-TXN-004");
        assertThat(store.rows("txn_first")).singleElement()
                .satisfies(r -> assertThat(r.get("value").s()).isEqualTo("old"));
    }

    @Test
    void first_failed_action_in_transact_order_wins_when_several_conditions_fail() {
        store.failure = "alignedConditionAll";
        Throwable error = catchThrowable(() -> tx(t -> {
            writer("txn_first").insert(row(1, "one"));
            writer("txn_second").update(row(2, "new"), row(2, "old"));
            return null;
        }));
        assertThat(isOrCausedBy(error, MithraUniqueIndexViolationException.class))
                .as("first TransactItems action is the insert, so unique-index wins. actual=%s",
                        messages(error))
                .isTrue();
        assertThat(isOrCausedBy(error, MithraOptimisticLockException.class)).isFalse();
        assertThat(messages(error)).doesNotContain("RELADYNAMO-TXN-004");
    }

    @Test
    void aligned_upsert_condition_failure_stays_txn_004() {
        store.failure = "alignedCondition";
        Throwable error = catchThrowable(() -> tx(t -> {
            writer("txn_first").upsert(row(1, "one"));
            return null;
        }));
        assertThat(messages(error)).contains("RELADYNAMO-TXN-004");
        assertThat(isOrCausedBy(error, MithraUniqueIndexViolationException.class)).isFalse();
        assertThat(isOrCausedBy(error, MithraOptimisticLockException.class)).isFalse();
    }

    @Test
    void later_synchronization_failure_cannot_leave_a_commit_behind() {
        assertThatThrownBy(() -> tx(t -> {
            writer("txn_first").insert(row(1, "one"));
            t.registerSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    throw new IllegalStateException("later veto");
                }

                @Override
                public void afterCompletion(int status) {
                }
            });
            return null;
        }));
        assertThat(store.rows("txn_first")).isEmpty();
    }

    @Test
    void nested_commit_is_not_durable_before_outer_commit() {
        assertThatThrownBy(() -> tx(t -> {
            tx(nested -> {
                writer("txn_first").insert(row(1, "one"));
                return null;
            });
            throw new IllegalStateException("outer abort");
        }));
        assertThat(store.rows("txn_first")).isEmpty();
    }

    @Test
    void nested_read_with_parent_staged_writes_is_refused() {
        Throwable error = catchThrowable(() -> tx(t -> {
            writer("txn_first").insert(row(1, "new"));
            tx(nested -> {
                persister().count(null);
                return null;
            });
            return null;
        }));
        assertThat(messages(error)).contains("RELADYNAMO-TXN-006");
        assertThat(store.rows("txn_first")).isEmpty();
    }

    private DynamoDbPersister persister() {
        com.gs.fw.common.mithra.finder.RelatedFinder finder =
                (com.gs.fw.common.mithra.finder.RelatedFinder) Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{com.gs.fw.common.mithra.finder.RelatedFinder.class},
                        (p, m, a) -> null);
        return new DynamoDbPersister(finder, mapping("txn_first"), writer("txn_first"));
    }

    @Test
    void unsupported_participation_modes_are_refused_by_name() {
        com.gs.fw.common.mithra.behavior.txparticipation.TxParticipationMode[] modes = {
                new com.gs.fw.common.mithra.behavior.txparticipation.FullTransactionalParticipationMode(),
                new com.gs.fw.common.mithra.behavior.txparticipation.ReadCacheWithOptimisticLockingTxParticipationMode(),
                new com.gs.fw.common.mithra.behavior.txparticipation.ReadCacheUpdateCausesRefreshAndLockTxParticipationMode(),
                new com.gs.fw.common.mithra.behavior.txparticipation.ReadCacheUpdateNotAllowedTxParticipationMode()
        };
        for (com.gs.fw.common.mithra.behavior.txparticipation.TxParticipationMode mode : modes) {
            Throwable error = catchThrowable(() -> tx(t -> {
                persister().setTxParticipationMode(mode, t);
                return null;
            }));
            assertThat(messages(error)).contains("RELADYNAMO-TXN-007", mode.getClass().getSimpleName());
        }
    }

    @Test
    void database_query_with_pending_writes_refuses_instead_of_returning_stale_results() {
        Throwable error = catchThrowable(() -> tx(t -> {
            writer("txn_first").insert(row(1, "new"));
            persister().count(null);
            return null;
        }));
        assertThat(messages(error)).contains("RELADYNAMO-TXN-006");
        assertThat(store.rows("txn_first")).isEmpty();
    }

    @Test
    void exhausted_ambiguity_is_unknown_and_blocks_subsequent_writes_on_that_client() {
        store.failure = "timeoutAlways";
        Throwable error = catchThrowable(() -> tx(t -> {
            writer("txn_first").insert(row(1, "one"));
            return null;
        }));
        assertThat(messages(error)).contains("RELADYNAMO-TXN-003", "UNKNOWN", "ClientRequestToken=");
        int attempts = store.requests.size();
        assertThat(attempts).isBetween(2, 4);
        assertThat(store.requests).allMatch(r -> r.equals(store.requests.get(0)));
        Throwable retry = catchThrowable(() -> writer("txn_first").insert(row(2, "do not replay")));
        assertThat(messages(retry)).contains("RELADYNAMO-TXN-003");
        assertThat(store.requests).hasSize(attempts);
        assertThat(store.rows("txn_first")).hasSize(1);
    }

    public static class Store {
        final Map<String, Map<String, Map<String, AttributeValue>>> tables = new HashMap<>();
        final List<TransactWriteItemsRequest> requests = new ArrayList<>();
        final Map<String, TransactWriteItemsRequest> tokens = new HashMap<>();
        String failure;
        public DynamoDbClient client = (DynamoDbClient) Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class<?>[]{DynamoDbClient.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    Object arg = args == null ? null : args[0];
                    if (name.equals("putItem")) {
                        PutItemRequest.Builder b = PutItemRequest.builder();
                        if (arg instanceof Consumer) {
                            ((Consumer<PutItemRequest.Builder>) arg).accept(b);
                        }
                        PutItemRequest r = arg instanceof PutItemRequest ? (PutItemRequest) arg : b.build();
                        put(r.tableName(), r.item());
                        return PutItemResponse.builder().build();
                    }
                    if (name.equals("deleteItem")) {
                        DeleteItemRequest.Builder b = DeleteItemRequest.builder();
                        if (arg instanceof Consumer) {
                            ((Consumer<DeleteItemRequest.Builder>) arg).accept(b);
                        }
                        DeleteItemRequest r = arg instanceof DeleteItemRequest ? (DeleteItemRequest) arg : b.build();
                        delete(r.tableName(), r.key());
                        return DeleteItemResponse.builder().build();
                    }
                    if (name.equals("batchWriteItem")) {
                        BatchWriteItemRequest r = (BatchWriteItemRequest) arg;
                        r.requestItems().forEach((table, writes) -> writes.forEach(w -> {
                            if (w.putRequest() != null) {
                                put(table, w.putRequest().item());
                            } else {
                                delete(table, w.deleteRequest().key());
                            }
                        }));
                        return BatchWriteItemResponse.builder().build();
                    }
                    if (name.equals("transactWriteItems")) {
                        return transact((TransactWriteItemsRequest) arg);
                    }
                    if (name.equals("serviceName")) {
                        return "DynamoDb";
                    }
                    if (name.equals("close")) {
                        return null;
                    }
                    if (name.equals("toString")) {
                        return "TransactionTestStore";
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("equals")) {
                        return proxy == arg;
                    }
                    throw new UnsupportedOperationException(name);
                });

        public Store() {
        }

        void reset() {
            tables.clear();
            requests.clear();
            tokens.clear();
            failure = null;
        }

        public List<Map<String, AttributeValue>> rows(String table) {
            return new ArrayList<>(tables.getOrDefault(table, Collections.emptyMap()).values());
        }

        String key(Map<String, AttributeValue> item) {
            return item.get("pk").s() + "|" + item.get("sk").s();
        }

        /**
         * Synthetic cancellation reasons. {@code condition} is deliberately unaligned (one
         * reason, many actions) so TXN-004 stays the outcome. {@code alignedCondition*}
         * matches TransactItems length the way the real service does.
         */
        List<CancellationReason> cancellationReasons(String f, TransactWriteItemsRequest request) {
            if ("alignedCondition".equals(f) || "alignedConditionAll".equals(f)) {
                List<CancellationReason> reasons = new ArrayList<>();
                int n = request.transactItems().size();
                for (int i = 0; i < n; i++) {
                    boolean fail = "alignedConditionAll".equals(f) || i == 0;
                    reasons.add(CancellationReason.builder()
                            .code(fail ? "ConditionalCheckFailed" : "None")
                            .message(fail ? "The conditional request failed." : null)
                            .build());
                }
                return reasons;
            }
            return Collections.singletonList(CancellationReason.builder()
                    .code("conflict".equals(f) ? "TransactionConflict" : "ConditionalCheckFailed")
                    .build());
        }

        void put(String table, Map<String, AttributeValue> item) {
            tables.computeIfAbsent(table, k -> new HashMap<>()).put(key(item), item);
        }

        void delete(String table, Map<String, AttributeValue> key) {
            tables.computeIfAbsent(table, k -> new HashMap<>()).remove(key(key));
        }

        TransactWriteItemsResponse transact(TransactWriteItemsRequest request) {
            requests.add(request);
            String f = failure;
            if (!"timeoutAlways".equals(f)) {
                failure = null;
            }
            if ("conflict".equals(f) || "condition".equals(f)
                    || "alignedCondition".equals(f) || "alignedConditionAll".equals(f)) {
                throw TransactionCanceledException.builder()
                        .cancellationReasons(cancellationReasons(f, request))
                        .build();
            }
            if ("progress".equals(f)) {
                throw TransactionInProgressException.builder().message("in progress").build();
            }
            if (tokens.containsKey(request.clientRequestToken())) {
                assertThat(request).isEqualTo(tokens.get(request.clientRequestToken()));
            } else {
                Set<String> seen = new HashSet<>();
                request.transactItems().forEach(a -> {
                    String table = a.put() != null ? a.put().tableName() : a.delete().tableName();
                    Map<String, AttributeValue> item = a.put() != null ? a.put().item() : a.delete().key();
                    assertThat(seen.add(table + "|" + key(item))).isTrue();
                });
                request.transactItems().forEach(a -> {
                    if (a.put() != null) {
                        put(a.put().tableName(), a.put().item());
                    } else {
                        delete(a.delete().tableName(), a.delete().key());
                    }
                });
                tokens.put(request.clientRequestToken(), request);
            }
            if ("timeoutAfter".equals(f) || "timeoutAlways".equals(f)) {
                throw ApiCallTimeoutException.create(100);
            }
            return TransactWriteItemsResponse.builder().build();
        }
    }
}
