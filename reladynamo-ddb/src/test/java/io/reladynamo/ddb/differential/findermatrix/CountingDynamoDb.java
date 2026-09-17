package io.reladynamo.ddb.differential.findermatrix;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Counting AWS SDK v2 client. Only the Request-object overloads increment, so a builder
 * Consumer that forwards to the real client is not double-counted.
 */
final class CountingDynamoDb {

    private CountingDynamoDb() {
    }

    static DynamoDbClient wrap(DynamoDbClient real, RequestCounters counters) {
        return (DynamoDbClient) Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class[] {DynamoDbClient.class},
                new Handler(real, counters));
    }

    private static final class Handler implements InvocationHandler {
        private final DynamoDbClient real;
        private final RequestCounters counters;

        Handler(DynamoDbClient real, RequestCounters counters) {
            this.real = real;
            this.counters = counters;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && args[0] != null) {
                Object arg = args[0];
                if (arg instanceof QueryRequest) {
                    QueryRequest req = (QueryRequest) arg;
                    counters.query.incrementAndGet();
                    counters.record("Query", detail(req.tableName(), req.indexName(),
                            String.valueOf(req.exclusiveStartKey())));
                } else if (arg instanceof GetItemRequest) {
                    GetItemRequest req = (GetItemRequest) arg;
                    counters.getItem.incrementAndGet();
                    counters.record("GetItem", detail(req.tableName(), null, keys(req.key())));
                } else if (arg instanceof ExecuteStatementRequest) {
                    ExecuteStatementRequest req = (ExecuteStatementRequest) arg;
                    counters.executeStatement.incrementAndGet();
                    counters.record("ExecuteStatement", String.valueOf(req.statement()));
                } else if (arg instanceof BatchGetItemRequest) {
                    counters.batchGetItem.incrementAndGet();
                    counters.record("BatchGetItem", ((BatchGetItemRequest) arg).requestItems().keySet().toString());
                } else if (arg instanceof ScanRequest) {
                    ScanRequest req = (ScanRequest) arg;
                    counters.scan.incrementAndGet();
                    counters.record("Scan", detail(req.tableName(), req.indexName(), null));
                }
            }
            try {
                Object result = method.invoke(real, args);
                if (result instanceof QueryResponse) {
                    QueryResponse r = (QueryResponse) result;
                    counters.record("QueryResponse", "count=" + r.count()
                            + " last=" + (r.hasLastEvaluatedKey() ? "yes" : "no"));
                } else if (result instanceof GetItemResponse) {
                    GetItemResponse r = (GetItemResponse) result;
                    counters.record("GetItemResponse", r.hasItem() ? "hit" : "miss");
                } else if (result instanceof ExecuteStatementResponse) {
                    ExecuteStatementResponse r = (ExecuteStatementResponse) result;
                    counters.record("ExecuteStatementResponse",
                            "items=" + (r.hasItems() ? r.items().size() : 0));
                } else if (result instanceof BatchGetItemResponse) {
                    counters.record("BatchGetItemResponse", "ok");
                } else if (result instanceof ScanResponse) {
                    ScanResponse r = (ScanResponse) result;
                    counters.record("ScanResponse", "count=" + r.count());
                }
                return result;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw e;
            }
        }

        private static String detail(String table, String index, String extra) {
            return "table=" + table + " index=" + index + (extra == null ? "" : " " + extra);
        }

        private static String keys(Map<?, ?> key) {
            return key == null ? "null" : key.toString();
        }
    }
}
