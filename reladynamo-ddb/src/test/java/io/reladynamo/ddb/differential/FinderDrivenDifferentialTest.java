package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.core.temporal.TemporalEncoder;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-path equivalence: the same <em>finder call</em> answered by both stores.
 *
 * <p>Every other differential test compares stored rows — it writes through the adapter and reads
 * back, which proves storage, key derivation and codec fidelity. It does not prove that a query
 * <em>planned and executed</em> against DynamoDB returns what Reladomo-over-H2 returns.
 *
 * <p>This closes that gap. The portal is rebound to a {@link DynamoDbPersister} between runs, so the
 * identical {@code DiffBalanceFinder} call is served first by JDBC and then by the adapter, and the
 * two result sets are compared. The rebinding itself is the mechanism the spike proved: one
 * {@code setMithraObjectReader} call moves both halves.
 *
 * <p>Reladomo caches aggressively, so each run bypasses the cache — otherwise the second query would
 * be answered from the first query's results and the test would pass without DynamoDB doing anything.
 */
class FinderDrivenDifferentialTest {

    private static final long P0 = utc(2020, 5, 1, 9, 0, 0, 0).getTime();
    private static final long P1 = utc(2020, 5, 1, 9, 0, 1, 0).getTime();

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static DynamoDbWriter writer;
    private static DynamoDbPersister adapter;
    private static MithraObjectReader jdbcReader;
    private static MithraAbstractObjectPortal portal;

    @BeforeAll
    static void setUp() throws Exception {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();

        StringBuilder xml = new StringBuilder();
        try (InputStream in = FinderDrivenDifferentialTest.class
                .getResourceAsStream("/reladomo/models/DiffBalance.xml");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                xml.append(line).append('\n');
            }
        }
        mapping = new MithraObjectXmlParser().parse(xml.toString());
        ItemCodec codec = new ItemCodec(mapping);

        ddb.client().createTable(b -> b.tableName(mapping.tableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));

        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        adapter = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping, writer,
                new QueryPlanner(), new QueryPlanExecutor(ddb.client(), codec),
                PhysicalDesign.builder(mapping).infinityFrom(DiffBalanceFinder.getFinderInstance()).build(), PlannerConfig.builder().build());

        portal = (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();
        jdbcReader = portal.getDatabaseObject();

        seed(900, 25.0, "finder-driven");
        mirrorToDynamo(900);
    }

    @AfterAll
    static void tearDown() {
        if (portal != null && jdbcReader != null) {
            portal.setMithraObjectReader(jdbcReader);
        }
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void the_same_finder_call_returns_the_same_rows_from_either_store() {
        assertFinderAgrees(currentRowOperation(900));
    }

    @Test
    void count_agrees_between_the_reference_and_the_adapter() {
        Operation op = currentRowOperation(900);
        int h2Count = runFinder(op).size();

        int dynamoCount;
        portal.setMithraObjectReader(adapter);
        try {
            dynamoCount = adapter.count(op);
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
        assertThat(dynamoCount).isEqualTo(h2Count);
    }

    @Test
    void current_row_query_plan_queries_the_item_collection_with_asof_containment() {
        Operation op = currentRowOperation(900);
        QueryPlan plan = new QueryPlanner().plan(
                new AnalyzedOperation(op), PhysicalDesign.builder(mapping).infinityFrom(DiffBalanceFinder.getFinderInstance()).build());

        System.out.println("FINDER_DRIVEN_PLAN=" + plan.toAssertableString());
        System.out.println("FINDER_DRIVEN_PK=" + plan.keyCondition().encodedPartitionKey());
        System.out.println("FINDER_DRIVEN_SK=" + plan.keyCondition().encodedSortKey());
        System.out.println("FINDER_DRIVEN_KEY=" + plan.keyConditionExpression());
        System.out.println("FINDER_DRIVEN_FILTER=" + plan.filterExpression());
        System.out.println("FINDER_DRIVEN_NAMES=" + plan.expressionAttributeNames());
        System.out.println("FINDER_DRIVEN_VALUES=" + plan.expressionAttributeValues());
        System.out.println("FINDER_DRIVEN_RESIDUAL=" + plan.residualOperationDump());
        System.out.println("FINDER_DRIVEN_FAST=" + plan.fastPath());

        assertThat(plan.kind())
                .as(plan.toAssertableString())
                .isEqualTo(PlanKind.QUERY);
        assertThat(plan.indexName()).isEqualTo("PRIMARY");
        assertThat(plan.keyConditionExpression()).isEqualTo("#pk = :pk");
        assertThat(plan.keyCondition().encodedPartitionKey()).isEqualTo("v1#DIFFBALANCE#900");
        assertThat(plan.keyCondition().encodedSortKey())
                .as("as-of does not name the stored FROM, so SK must stay unbound")
                .isNull();
        assertThat(plan.fastPath()).isEqualTo(QueryPlan.FAST_PATH_CURRENT_ASOF);
        assertThat(plan.residual().isEmpty()).isTrue();
        assertThat(plan.expressionAttributeNames().values()).contains("OUT_Z", "FROM_Z", "THRU_Z");
        assertThat(plan.expressionAttributeNames().values())
                .as("processing infinity is TO equality, not IN_Z half-open containment: %s",
                        plan.toAssertableString())
                .doesNotContain("IN_Z");
        assertThat(plan.filterExpression())
                .as("current-row plan must equality-match OUT_Z, not contain processing FROM: %s",
                        plan.toAssertableString())
                .contains("=");

        String encodedInf = TemporalEncoder.encode(
                DiffBalanceFinder.processingDate().getInfinityDate());
        boolean bindsReladomoInfinity = false;
        for (io.reladynamo.core.plan.ExpressionValue value : plan.expressionAttributeValues().values()) {
            if (value.s() != null && encodedInf.equals(value.s())) {
                bindsReladomoInfinity = true;
            }
        }
        assertThat(bindsReladomoInfinity)
                .as("processing infinity filter must bind Reladomo's sentinel %s, values=%s",
                        encodedInf, plan.expressionAttributeValues())
                .isTrue();
    }

    @Test
    void as_of_a_past_business_date_agrees_between_stores() {
        int id = 920;
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance b = new DiffBalance(utc(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(11.0);
            b.setLabel("past-biz");
            b.insert();
            return null;
        });
        mirrorToDynamo(id);

        Operation op = DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().eq(utc(2026, 3, 1)))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
        assertFinderAgrees(op);
    }

    @Test
    void as_of_a_past_processing_date_agrees_between_stores() {
        int id = 921;
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance b = new DiffBalance(utc(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(10.0);
            b.setLabel("before");
            b.insert();
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance b = DiffBalanceFinder.findOne(
                    DiffBalanceFinder.balanceId().eq(id)
                            .and(DiffBalanceFinder.businessDate().eq(utc(2026, 1, 1)))
                            .and(DiffBalanceFinder.processingDate().eq(
                                    DiffBalanceFinder.processingDate().getInfinityDate())));
            b.setQuantity(20.0);
            return null;
        });
        mirrorToDynamo(id);

        Operation op = DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().eq(utc(2026, 6, 1)))
                .and(DiffBalanceFinder.processingDate().eq(new Timestamp(P0)));
        assertFinderAgrees(op);
    }

    @Test
    void finite_business_date_to_agrees_between_stores() {
        int id = 922;
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance b = new DiffBalance(utc(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(7.0);
            b.setLabel("until");
            b.insertUntil(utc(2026, 6, 1));
            return null;
        });
        mirrorToDynamo(id);

        Operation inside = DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().eq(utc(2026, 3, 1)))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
        assertFinderAgrees(inside);

        Operation atExclusiveTo = DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().eq(utc(2026, 6, 1)))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
        List<Map<String, Object>> h2AtTo = runFinder(atExclusiveTo);
        assertThat(h2AtTo)
                .as("insertUntil(June 1) is half-open: as-of the exclusive TO is empty on H2")
                .isEmpty();
        portal.setMithraObjectReader(adapter);
        try {
            assertThat(runFinder(atExclusiveTo))
                    .as("exclusive businessDateTo must also be empty on the adapter")
                    .isEmpty();
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    // --- helpers -------------------------------------------------------------------

    private static void assertFinderAgrees(Operation op) {
        List<Map<String, Object>> fromH2 = runFinder(op);
        assertThat(fromH2).as("the H2 reference must return rows, or the comparison is vacuous")
                .isNotEmpty();

        List<Map<String, Object>> fromDynamo;
        portal.setMithraObjectReader(adapter);
        try {
            fromDynamo = runFinder(op);
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }

        RowSetDiff diff = TemporalRowSetDiffer.compare(fromH2, fromDynamo);
        assertThat(diff.isIdentical())
                .as("the same finder call disagreed between stores:%n%s", diff.describe())
                .isTrue();
    }

    private static Operation currentRowOperation(int balanceId) {
        return DiffBalanceFinder.balanceId().eq(balanceId)
                .and(DiffBalanceFinder.businessDate().eq(utc(2026, 6, 1)))
                .and(DiffBalanceFinder.processingDate().eq(DiffBalanceFinder.processingDate().getInfinityDate()));
    }

    /** Cache bypass matters: without it the adapter never gets asked. */
    private static List<Map<String, Object>> runFinder(Operation op) {
        DiffBalanceList list = DiffBalanceFinder.findMany(op);
        list.setBypassCache(true);
        list.forceResolve();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            rows.add(MithraDataAccessor.extract(
                    DiffBalanceFinder.getFinderInstance(), list.get(i).zGetCurrentData()));
        }
        return rows;
    }

    private static void seed(int id, double qty, String label) {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
            DiffBalance b = new DiffBalance(utc(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(qty);
            b.setLabel(label);
            b.insert();
            return null;
        });
    }

    private static void mirrorToDynamo(int id) {
        DiffBalanceList list = DiffBalanceFinder.findMany(
                DiffBalanceFinder.balanceId().eq(id)
                        .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                        .and(DiffBalanceFinder.processingDate().equalsEdgePoint()));
        list.setBypassCache(true);
        for (int i = 0; i < list.size(); i++) {
            writer.upsert(MithraDataAccessor.extract(
                    DiffBalanceFinder.getFinderInstance(), list.get(i).zGetCurrentData()));
        }
    }

    private static Timestamp utc(int y, int mo, int d) {
        return utc(y, mo, d, 0, 0, 0, 0);
    }

    private static Timestamp utc(int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(ms * 1_000_000);
        return t;
    }
}
