package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.differential.domain.DiffEntry;
import io.reladynamo.ddb.differential.domain.DiffEntryFinder;
import io.reladynamo.ddb.differential.domain.DiffEntryList;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Relationships and deep-fetch through the adapter — the last untested Reladomo feature.
 *
 * <p>Storage round-trip of child rows is the same shape as the other differential tests: Reladomo
 * writes to H2, {@link DynamoDbWriter} mirrors, {@link TemporalRowSetDiffer} compares every
 * attribute including the four temporal boundaries.
 *
 * <p>Relationship navigation is different. Reladomo resolves {@code balance.getEntries()} /
 * {@code deepFetch(entries)} by calling {@code find} on the <em>child</em> portal with an FK
 * predicate, not the child's primary key. A test that only checks returned data cannot tell a
 * deep-fetch from an N+1: on DynamoDB that is the difference between one billed request and one
 * per child. Request counts are therefore part of the contract.
 *
 * <p><b>Finding 15.</b> Child rows round-trip on the base table. Navigation is a GSI whose
 * partition key is the FK {@code balanceId}, with {@code IN} fan-out — one Query per parent,
 * never a table Scan. An entity with no GSI on the FK still throws PLAN-001.
 */
class RelationshipDifferentialTest {

    /** Eight parents, three children each: enough that per-parent N+1 is still many requests. */
    private static final int PARENT_COUNT = 8;
    private static final int CHILDREN_PER_PARENT = 3;
    private static final int FIRST_BALANCE_ID = 8101;
    private static final int FIRST_ENTRY_ID = 9101;
    private static final long P0 = DifferentialSupport.utc(2020, 3, 1, 12, 0, 0, 0).getTime();

    private static LocalDynamoDb ddb;
    private static RequestCounter counter;
    private static DynamoDbClient countingClient;

    private static EntityMapping balanceMapping;
    private static EntityMapping entryMapping;
    private static ItemCodec balanceCodec;
    private static ItemCodec entryCodec;
    private static DynamoDbWriter balanceWriter;
    private static DynamoDbWriter entryWriter;
    private static DynamoDbPersister balanceAdapter;
    private static DynamoDbPersister entryAdapter;

    private static MithraAbstractObjectPortal balancePortal;
    private static MithraAbstractObjectPortal entryPortal;
    private static MithraObjectReader balanceJdbc;
    private static MithraObjectReader entryJdbc;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        counter = new RequestCounter();
        countingClient = counter.wrap(ddb.client());

        balanceMapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffBalance.xml"));
        entryMapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffEntry.xml"));
        balanceCodec = new ItemCodec(balanceMapping);
        entryCodec = new ItemCodec(entryMapping);

        GsiSpec entryFk = GsiSpec.foreignKey("gsi_balanceId", "balanceId");
        PhysicalDesign balanceDesign = PhysicalDesign.builder(balanceMapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance()).build();
        PhysicalDesign entryDesign = PhysicalDesign.builder(entryMapping)
                .infinityFrom(DiffEntryFinder.getFinderInstance())
                .addGsi(entryFk)
                .build();

        createPkSkTable(ddb.client(), balanceMapping.tableName());
        new TableCreator(ddb.client()).create(entryDesign);

        balanceWriter = new DynamoDbWriter(ddb.client(), balanceMapping, balanceCodec, new DefaultKeyStrategy());
        entryWriter = new DynamoDbWriter(ddb.client(), entryMapping, entryCodec, new DefaultKeyStrategy(),
                entryDesign.gsis());

        PlannerConfig plannerConfig = PlannerConfig.builder().build();
        balanceAdapter = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), balanceMapping, balanceWriter,
                new QueryPlanner(), new QueryPlanExecutor(countingClient, balanceCodec),
                balanceDesign,
                plannerConfig);
        entryAdapter = new DynamoDbPersister(
                DiffEntryFinder.getFinderInstance(), entryMapping, entryWriter,
                new QueryPlanner(), new QueryPlanExecutor(countingClient, entryCodec),
                entryDesign,
                plannerConfig);

        balancePortal = (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();
        entryPortal = (MithraAbstractObjectPortal) DiffEntryFinder.getMithraObjectPortal();
        balanceJdbc = balancePortal.getDatabaseObject();
        entryJdbc = entryPortal.getDatabaseObject();

        seedGraph();
        mirrorBalancesToDynamo();
        mirrorEntriesToDynamo();
    }

    @AfterAll
    static void tearDown() {
        if (balancePortal != null && balanceJdbc != null) {
            balancePortal.setMithraObjectReader(balanceJdbc);
        }
        if (entryPortal != null && entryJdbc != null) {
            entryPortal.setMithraObjectReader(entryJdbc);
        }
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void child_rows_round_trip_with_all_four_temporal_boundaries() {
        List<Map<String, Object>> reference = readAllEntryVersionsFromH2();
        assertThat(reference)
                .as("the H2 reference must produce child rows, otherwise the comparison is vacuous")
                .hasSize(PARENT_COUNT * CHILDREN_PER_PARENT);

        for (int i = 0; i < reference.size(); i++) {
            Map<String, Object> row = reference.get(i);
            assertThat(row.get("businessDateFrom")).as("FROM_Z on entry %s", i).isNotNull();
            assertThat(row.get("businessDateTo")).as("THRU_Z on entry %s", i).isNotNull();
            assertThat(row.get("processingDateFrom")).as("IN_Z on entry %s", i).isNotNull();
            assertThat(row.get("processingDateTo")).as("OUT_Z on entry %s", i).isNotNull();
        }

        List<Map<String, Object>> adapter = readAllEntryVersionsFromDynamo();
        RowSetDiff diff = TemporalRowSetDiffer.compare(reference, adapter);
        assertThat(diff.isIdentical())
                .as("H2 and DynamoDB disagree on DiffEntry rows:%n%s%nH2:%n%s%nDDB:%n%s",
                        diff.describe(),
                        DifferentialSupport.describeRows(reference),
                        DifferentialSupport.describeRows(adapter))
                .isTrue();
    }

    @Test
    void relationship_navigation_agrees_between_stores() {
        List<Map<String, Object>> fromH2 = entriesViaRelationshipOn(balanceJdbc, entryJdbc);
        assertThat(fromH2)
                .as("H2 relationship navigation must return the seeded children")
                .hasSize(PARENT_COUNT * CHILDREN_PER_PARENT);

        List<Map<String, Object>> fromAdapter = entriesViaRelationshipOn(balanceAdapter, entryAdapter);
        RowSetDiff diff = TemporalRowSetDiffer.compare(fromH2, fromAdapter);
        assertThat(diff.isIdentical())
                .as("relationship navigation disagreed between stores:%n%s%nH2:%n%s%nDDB:%n%s",
                        diff.describe(),
                        DifferentialSupport.describeRows(fromH2),
                        DifferentialSupport.describeRows(fromAdapter))
                .isTrue();
    }

    @Test
    void deep_fetch_issues_substantially_fewer_requests_than_one_per_child_row() {
        int childCount = PARENT_COUNT * CHILDREN_PER_PARENT;
        balancePortal.setMithraObjectReader(balanceAdapter);
        entryPortal.setMithraObjectReader(entryAdapter);
        try {
            DiffBalanceList parents = DiffBalanceFinder.findMany(currentParents());
            parents.setBypassCache(true);
            parents.forceResolve();
            assertThat(parents.size()).isEqualTo(PARENT_COUNT);

            // Parent PK lookups are a different path (already covered). Reset so the
            // remaining counts are the relationship fetch itself.
            counter.reset();
            parents.deepFetch(DiffBalanceFinder.entries());

            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < parents.size(); i++) {
                DiffEntryList entries = parents.get(i).getEntries();
                for (int j = 0; j < entries.size(); j++) {
                    rows.add(MithraDataAccessor.extract(
                            DiffEntryFinder.getFinderInstance(), entries.get(j).zGetCurrentData()));
                }
            }
            assertThat(rows)
                    .as("deep-fetch must actually return the children, not an empty list that would "
                            + "make a low request count look like a win")
                    .hasSize(childCount);

            int reads = counter.reads();
            System.out.println("deep-fetch measured reads=" + reads + " " + counter.describe()
                    + " children=" + childCount + " parents=" + PARENT_COUNT);
            assertThat(reads)
                    .as("deep-fetch issued %s query/getItem/scan calls for %s children across %s parents; "
                            + "one-per-child is the N+1 this test exists to catch (reads=%s details=%s)",
                            Integer.valueOf(reads), Integer.valueOf(childCount),
                            Integer.valueOf(PARENT_COUNT), Integer.valueOf(reads), counter.describe())
                    .isLessThan(childCount);
            // Measured: ONE query serves all 8 parents, because the executor batches the GSI
            // fan-out rather than issuing a query per partition key. Keep the strict bound — it is
            // met, and relaxing it would let a future regression to per-parent fan-out pass.
            assertThat(reads)
                    .as("deep-fetch must also beat one-request-per-parent N+1 (reads=%s parents=%s details=%s)",
                            Integer.valueOf(reads), Integer.valueOf(PARENT_COUNT), counter.describe())
                    .isLessThan(PARENT_COUNT);
        } finally {
            balancePortal.setMithraObjectReader(balanceJdbc);
            entryPortal.setMithraObjectReader(entryJdbc);
        }
    }

    // --- graph -------------------------------------------------------------------

    private static void seedGraph() {
        DifferentialSupport.inTransaction(P0, tx -> {
            int entryId = FIRST_ENTRY_ID;
            Timestamp business = DifferentialSupport.utc(2026, 1, 1);
            for (int p = 0; p < PARENT_COUNT; p++) {
                int balanceId = FIRST_BALANCE_ID + p;
                DiffBalance b = new DiffBalance(business);
                b.setBalanceId(balanceId);
                b.setQuantity(100.0 + p);
                b.setLabel("rel-parent-" + p);
                b.insert();
                for (int c = 0; c < CHILDREN_PER_PARENT; c++) {
                    DiffEntry e = new DiffEntry(business);
                    e.setEntryId(entryId++);
                    e.setBalanceId(balanceId);
                    e.setAmount(10.0 + c + (p * 0.01));
                    e.setLabel("rel-child-" + p + "-" + c);
                    e.insert();
                }
            }
            return null;
        });
    }

    private static void mirrorBalancesToDynamo() {
        DiffBalanceList list = DiffBalanceFinder.findMany(parentEdgePoint());
        list.setBypassCache(true);
        for (int i = 0; i < list.size(); i++) {
            balanceWriter.insert(MithraDataAccessor.extract(
                    DiffBalanceFinder.getFinderInstance(), list.get(i).zGetCurrentData()));
        }
    }

    private static void mirrorEntriesToDynamo() {
        DiffEntryList list = DiffEntryFinder.findMany(entryEdgePoint());
        list.setBypassCache(true);
        for (int i = 0; i < list.size(); i++) {
            entryWriter.insert(MithraDataAccessor.extract(
                    DiffEntryFinder.getFinderInstance(), list.get(i).zGetCurrentData()));
        }
    }

    private static List<Map<String, Object>> readAllEntryVersionsFromH2() {
        DiffEntryList list = DiffEntryFinder.findMany(entryEdgePoint());
        list.setBypassCache(true);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); i++) {
            rows.add(MithraDataAccessor.extract(
                    DiffEntryFinder.getFinderInstance(), list.get(i).zGetCurrentData()));
        }
        return rows;
    }

    private static List<Map<String, Object>> readAllEntryVersionsFromDynamo() {
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int lastEntry = FIRST_ENTRY_ID + (PARENT_COUNT * CHILDREN_PER_PARENT);
        for (int id = FIRST_ENTRY_ID; id < lastEntry; id++) {
            String pk = keys.partitionKey(entryMapping,
                    Collections.singletonMap("entryId", Integer.valueOf(id)));
            Map<String, AttributeValue> values = new HashMap<String, AttributeValue>();
            values.put(":pk", AttributeValue.builder().s(pk).build());
            Map<String, AttributeValue> start = null;
            do {
                final Map<String, AttributeValue> exclusiveStart = start;
                software.amazon.awssdk.services.dynamodb.model.QueryResponse r = ddb.client().query(b -> {
                    b.tableName(entryMapping.tableName()).keyConditionExpression("pk = :pk")
                            .expressionAttributeValues(values).consistentRead(true);
                    if (exclusiveStart != null && !exclusiveStart.isEmpty()) {
                        b.exclusiveStartKey(exclusiveStart);
                    }
                });
                for (Map<String, AttributeValue> item : r.items()) {
                    rows.add(entryCodec.decode(item));
                }
                start = r.lastEvaluatedKey();
            } while (start != null && !start.isEmpty());
        }
        return rows;
    }

    /**
     * Same finder call on both stores: current as-of parents, deep-fetch children, extract the
     * child data objects. Cache is bypassed so the second store cannot be answered from the first.
     */
    private static List<Map<String, Object>> entriesViaRelationshipOn(
            MithraObjectReader balanceReader, MithraObjectReader entryReader) {
        balancePortal.setMithraObjectReader(balanceReader);
        entryPortal.setMithraObjectReader(entryReader);
        try {
            DiffBalanceList parents = DiffBalanceFinder.findMany(currentParents());
            parents.setBypassCache(true);
            parents.deepFetch(DiffBalanceFinder.entries());
            parents.forceResolve();
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < parents.size(); i++) {
                DiffEntryList entries = parents.get(i).getEntries();
                for (int j = 0; j < entries.size(); j++) {
                    rows.add(MithraDataAccessor.extract(
                            DiffEntryFinder.getFinderInstance(), entries.get(j).zGetCurrentData()));
                }
            }
            return rows;
        } finally {
            balancePortal.setMithraObjectReader(balanceJdbc);
            entryPortal.setMithraObjectReader(entryJdbc);
        }
    }

    private static Operation currentParents() {
        // Equality OR, not a PK range: the planner will not Query a range of partition keys.
        Operation ids = DiffBalanceFinder.balanceId().eq(FIRST_BALANCE_ID);
        for (int i = 1; i < PARENT_COUNT; i++) {
            ids = ids.or(DiffBalanceFinder.balanceId().eq(FIRST_BALANCE_ID + i));
        }
        return ids
                .and(DiffBalanceFinder.businessDate().eq(DifferentialSupport.utc(2026, 6, 1)))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
    }

    private static Operation parentEdgePoint() {
        return DiffBalanceFinder.balanceId().greaterThanEquals(FIRST_BALANCE_ID)
                .and(DiffBalanceFinder.balanceId().lessThan(FIRST_BALANCE_ID + PARENT_COUNT))
                .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                .and(DiffBalanceFinder.processingDate().equalsEdgePoint());
    }

    private static Operation entryEdgePoint() {
        return DiffEntryFinder.entryId().greaterThanEquals(FIRST_ENTRY_ID)
                .and(DiffEntryFinder.entryId().lessThan(
                        FIRST_ENTRY_ID + (PARENT_COUNT * CHILDREN_PER_PARENT)))
                .and(DiffEntryFinder.businessDate().equalsEdgePoint())
                .and(DiffEntryFinder.processingDate().equalsEdgePoint());
    }

    private static void createPkSkTable(DynamoDbClient client, String tableName) {
        client.createTable(b -> b.tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
    }

    /**
     * Counts DynamoDB read calls. Reladomo deep-fetch that silently becomes per-row GetItem is
     * the billed N+1 this suite is for; counting only returned rows cannot see it.
     */
    static final class RequestCounter {
        private final AtomicInteger query = new AtomicInteger();
        private final AtomicInteger getItem = new AtomicInteger();
        private final AtomicInteger scan = new AtomicInteger();

        void reset() {
            query.set(0);
            getItem.set(0);
            scan.set(0);
        }

        int reads() {
            return query.get() + getItem.get() + scan.get();
        }

        String describe() {
            return "query=" + query.get() + " getItem=" + getItem.get() + " scan=" + scan.get();
        }

        DynamoDbClient wrap(final DynamoDbClient real) {
            return (DynamoDbClient) Proxy.newProxyInstance(
                    DynamoDbClient.class.getClassLoader(),
                    new Class[] {DynamoDbClient.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if ("query".equals(name) || "executeStatement".equals(name)
                                    || "batchExecuteStatement".equals(name)) {
                                query.incrementAndGet();
                            } else if ("getItem".equals(name) || "batchGetItem".equals(name)) {
                                getItem.incrementAndGet();
                            } else if ("scan".equals(name)) {
                                scan.incrementAndGet();
                            }
                            try {
                                return method.invoke(real, args);
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
                    });
        }
    }
}
