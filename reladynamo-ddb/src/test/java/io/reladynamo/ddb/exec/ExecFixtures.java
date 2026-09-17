package io.reladynamo.ddb.exec;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.ddb.codec.ItemCodec;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class ExecFixtures {

    static final String PK = "pk";
    static final String SK = "sk";

    private ExecFixtures() {
    }

    static EntityMapping customerMapping(String tableName) {
        List<AttributeMapping> attributes = new ArrayList<AttributeMapping>();
        attributes.add(new AttributeMapping("id", "id", "long", true, false));
        attributes.add(new AttributeMapping("name", "name", "String", false, false));
        attributes.add(new AttributeMapping("status", "status", "String", false, true));
        return new EntityMapping("Customer", tableName, TemporalMapping.none(), attributes);
    }

    static Map<String, Object> customer(long id, String name, String status) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Long.valueOf(id));
        row.put("name", name);
        row.put("status", status);
        return row;
    }

    static void putCustomer(DynamoDbClient client, EntityMapping mapping, ItemCodec codec,
                            Map<String, Object> row) {
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        pkValues.put("id", row.get("id"));
        String pk = keys.partitionKey(mapping, pkValues);
        String sk = keys.sortKey(mapping, null, null);
        Map<String, AttributeValue> item = new LinkedHashMap<String, AttributeValue>(codec.encode(row));
        item.put(PK, AttributeValue.builder().s(pk).build());
        item.put(SK, AttributeValue.builder().s(sk).build());
        client.putItem(PutItemRequest.builder().tableName(mapping.tableName()).item(item).build());
    }

    static String encodedPk(EntityMapping mapping, long id) {
        Map<String, Object> pkValues = new LinkedHashMap<String, Object>();
        pkValues.put("id", Long.valueOf(id));
        return new DefaultKeyStrategy().partitionKey(mapping, pkValues);
    }

    static QueryPlan.Builder basePlan(EntityMapping mapping) {
        return QueryPlan.builder()
                .className(mapping.className())
                .tableName(mapping.tableName())
                .indexName("PRIMARY")
                .consistentRead(true)
                .scanIndexForward(true);
    }

    /**
     * Counts getItem / query / scan / batchGetItem invocations while delegating everything else.
     */
    static final class CountingClient {
        final AtomicInteger reads = new AtomicInteger();
        final DynamoDbClient client;

        CountingClient(DynamoDbClient real) {
            this.client = (DynamoDbClient) Proxy.newProxyInstance(
                    DynamoDbClient.class.getClassLoader(),
                    new Class[]{DynamoDbClient.class},
                    new CountingHandler(real, reads));
        }
    }

    private static final class CountingHandler implements InvocationHandler {
        private final DynamoDbClient real;
        private final AtomicInteger reads;

        CountingHandler(DynamoDbClient real, AtomicInteger reads) {
            this.real = real;
            this.reads = reads;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getItem".equals(name) || "query".equals(name) || "scan".equals(name)
                    || "batchGetItem".equals(name) || "executeStatement".equals(name)
                    || "batchExecuteStatement".equals(name)) {
                reads.incrementAndGet();
            }
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            try {
                return method.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    static Operation residualNameEquals(String expected) {
        return proxyOperation(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("matches".equals(method.getName())) {
                    Object candidate = args[0];
                    if (!(candidate instanceof Map)) {
                        return Boolean.FALSE;
                    }
                    Object name = ((Map<?, ?>) candidate).get("name");
                    return Boolean.valueOf(expected.equals(name));
                }
                return defaultOp(proxy, method, args, "NameEq(" + expected + ")");
            }
        });
    }

    static Operation residualNullMatches() {
        return proxyOperation(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("matches".equals(method.getName())) {
                    return null;
                }
                return defaultOp(proxy, method, args, "NullMatchesOp");
            }
        });
    }

    static Operation residualNameOrNull(String passName) {
        return proxyOperation(new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("matches".equals(method.getName())) {
                    Object candidate = args[0];
                    if (!(candidate instanceof Map)) {
                        return null;
                    }
                    Object name = ((Map<?, ?>) candidate).get("name");
                    if (passName.equals(name)) {
                        return Boolean.TRUE;
                    }
                    if ("null-me".equals(name)) {
                        return null;
                    }
                    return Boolean.FALSE;
                }
                return defaultOp(proxy, method, args, "NameOrNull(" + passName + ")");
            }
        });
    }

    private static Operation proxyOperation(InvocationHandler handler) {
        return (Operation) Proxy.newProxyInstance(
                Operation.class.getClassLoader(),
                new Class[]{Operation.class},
                handler);
    }

    private static Object defaultOp(Object proxy, Method method, Object[] args, String dump) {
        if ("toString".equals(method.getName()) || "zToString".equals(method.getName())) {
            return dump;
        }
        if ("hashCode".equals(method.getName())) {
            return Integer.valueOf(System.identityHashCode(proxy));
        }
        if ("equals".equals(method.getName())) {
            return Boolean.valueOf(proxy == args[0]);
        }
        Class<?> rt = method.getReturnType();
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == int.class) {
            return Integer.valueOf(0);
        }
        return null;
    }
}
