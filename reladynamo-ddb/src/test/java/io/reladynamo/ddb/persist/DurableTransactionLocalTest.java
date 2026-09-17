package io.reladynamo.ddb.persist;

import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionInProgressException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Same acceptance contract over the test kit's genuine DynamoDB Local; never silently skipped. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DurableTransactionLocalTest extends DurableTransactionTest {

    @Override
    protected Store createStore() {
        return new LocalStore();
    }

    @AfterAll
    void closeLocal() {
        ((LocalStore) store).local.close();
    }

    @Test
    void duplicate_insert_in_a_transaction_is_refused_without_mutating() {
        DynamoDbWriter writer = writer("txn_first");
        writer.insert(row(1, "first"));
        Throwable error = catchThrowable(() -> tx(t -> {
            writer.insert(row(1, "second"));
            return null;
        }));
        assertThat(isOrCausedBy(error, com.gs.fw.common.mithra.MithraUniqueIndexViolationException.class))
                .as("duplicate insert against a live table must surface unique-index, not TXN-004. actual=%s",
                        messages(error))
                .isTrue();
        assertThat(messages(error)).doesNotContain("RELADYNAMO-TXN-004");
        assertThat(store.rows("txn_first")).singleElement()
                .satisfies(r -> assertThat(r.get("value").s()).isEqualTo("first"));
    }

    private static final class LocalStore extends Store {
        final LocalDynamoDb local = LocalDynamoDb.start();
        final DynamoDbClient delegate = local.client();

        LocalStore() {
            for (String table : Arrays.asList("txn_first", "txn_second")) {
                delegate.createTable(b -> b.tableName(table)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(
                                AttributeDefinition.builder().attributeName("pk")
                                        .attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder().attributeName("sk")
                                        .attributeType(ScalarAttributeType.S).build())
                        .keySchema(
                                KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
            }
            client = (DynamoDbClient) Proxy.newProxyInstance(
                    DynamoDbClient.class.getClassLoader(),
                    new Class<?>[]{DynamoDbClient.class},
                    (p, m, a) -> {
                        if (m.getName().equals("transactWriteItems") && a[0] instanceof TransactWriteItemsRequest) {
                            return transact((TransactWriteItemsRequest) a[0]);
                        }
                        try {
                            return m.invoke(delegate, a);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        @Override
        void reset() {
            requests.clear();
            tokens.clear();
            failure = null;
            for (String table : Arrays.asList("txn_first", "txn_second")) {
                for (Map<String, AttributeValue> item : rows(table)) {
                    Map<String, AttributeValue> key = new LinkedHashMap<>();
                    key.put("pk", item.get("pk"));
                    key.put("sk", item.get("sk"));
                    delegate.deleteItem(b -> b.tableName(table).key(key));
                }
            }
        }

        @Override
        public List<Map<String, AttributeValue>> rows(String table) {
            List<Map<String, AttributeValue>> rows = new ArrayList<>();
            Map<String, AttributeValue> start = null;
            do {
                ScanRequest.Builder request = ScanRequest.builder()
                        .tableName(table)
                        .consistentRead(true);
                if (start != null && !start.isEmpty()) {
                    request.exclusiveStartKey(start);
                }
                ScanResponse page = delegate.scan(request.build());
                rows.addAll(page.items());
                start = page.lastEvaluatedKey();
            } while (start != null && !start.isEmpty());
            return rows;
        }

        @Override
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
            TransactWriteItemsResponse result = delegate.transactWriteItems(request);
            if ("timeoutAfter".equals(f) || "timeoutAlways".equals(f)) {
                throw software.amazon.awssdk.core.exception.ApiCallTimeoutException.create(100);
            }
            return result;
        }
    }
}
