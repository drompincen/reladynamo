package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.DifferentialSupport;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inspection acceptance case 5: complete-key queries with failing payload / as-of filters
 * return no row and count zero. R-03 evaluates the plan filter locally on GET_ITEM.
 * Request counters must show a point read, not a downgrade to Query.
 */
class AcceptanceCompleteKeyFilterTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static DynamoDbWriter writer;
    private static QueryPlanner planner;
    private static PhysicalDesign design;
    private static RequestCounters counters;
    private static RecordingPersister recording;
    private static DynamoDbPersister adapter;
    private static MithraObjectReader jdbcReader;
    private static MithraAbstractObjectPortal portal;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        mapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffBalance.xml"));
        ItemCodec codec = new ItemCodec(mapping);
        design = PhysicalDesign.builder(mapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance())
                .build();
        new TableCreator(ddb.client()).create(design);

        counters = new RequestCounters();
        DynamoDbClient counting = CountingDynamoDb.wrap(ddb.client(), counters);
        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        planner = new QueryPlanner();
        adapter = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping, writer,
                planner, new QueryPlanExecutor(counting, codec),
                design, PlannerConfig.builder().build());
        recording = new RecordingPersister(adapter);
        portal = (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();
        jdbcReader = portal.getDatabaseObject();
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
    void complete_pk_plus_failing_payload_returns_nothing_and_count_zero_via_getitem() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 12, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int id = 96201;
        writer.insert(balanceRow(id, 1.0d, "CLOSED", from, thru, inZ));

        Operation op = completeKey(id, from, inZ, utc(2026, 6, 1))
                .and(DiffBalanceFinder.label().eq("ACTIVE"));
        assertGetItemPlan(op);

        portal.setMithraObjectReader(recording.proxy());
        try {
            portal.clearQueryCache();
            portal.getCache().clear();
            counters.reset();
            recording.reset();

            DiffBalanceList list = DiffBalanceFinder.findMany(op);
            list.setBypassCache(true);
            list.forceResolve();
            assertThat(list)
                    .as("complete PK + label=ACTIVE must not return CLOSED")
                    .isEmpty();
            RequestAssertions.requireReaderEntered(recording.readerEntries(), "payload-fail find");
            RequestAssertions.requirePositiveDataReads(counters.dataReads(), "payload-fail find");
            RequestAssertions.requireZeroScan(counters.scan.get(), "payload-fail find");
            assertThat(counters.getItem.get())
                    .as("must be a point read, not a Query downgrade: %s", counters.describe())
                    .isGreaterThanOrEqualTo(1);
            assertThat(counters.query.get())
                    .as("Query would be a GET_ITEM downgrade: %s", counters.describe())
                    .isZero();

            counters.reset();
            recording.reset();
            assertThat(DiffBalanceFinder.findMany(op).count()).isZero();
            RequestAssertions.requireReaderEntered(recording.readerEntries(), "payload-fail count");
            assertThat(counters.getItem.get())
                    .as("count must also be a point read: %s", counters.describe())
                    .isGreaterThanOrEqualTo(1);
            assertThat(counters.query.get())
                    .as("count must not downgrade to Query: %s", counters.describe())
                    .isZero();
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    @Test
    void exact_dated_rectangle_with_failing_asof_returns_nothing_and_count_zero_via_getitem() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 6, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int id = 96202;
        writer.insert(balanceRow(id, 3.0d, "OPEN", from, thru, inZ));

        Operation op = completeKey(id, from, inZ, utc(2026, 7, 1));
        assertGetItemPlan(op);

        portal.setMithraObjectReader(recording.proxy());
        try {
            portal.clearQueryCache();
            portal.getCache().clear();
            counters.reset();
            recording.reset();

            DiffBalanceList list = DiffBalanceFinder.findMany(op);
            list.setBypassCache(true);
            list.forceResolve();
            assertThat(list)
                    .as("as-of July 1 is outside [Jan 1, June 1)")
                    .isEmpty();
            RequestAssertions.requireReaderEntered(recording.readerEntries(), "asof-fail find");
            RequestAssertions.requirePositiveDataReads(counters.dataReads(), "asof-fail find");
            RequestAssertions.requireZeroScan(counters.scan.get(), "asof-fail find");
            assertThat(counters.getItem.get())
                    .as("must be a point read, not a Query downgrade: %s", counters.describe())
                    .isGreaterThanOrEqualTo(1);
            assertThat(counters.query.get())
                    .as("Query would be a GET_ITEM downgrade: %s", counters.describe())
                    .isZero();

            counters.reset();
            recording.reset();
            assertThat(DiffBalanceFinder.findMany(op).count()).isZero();
            RequestAssertions.requireReaderEntered(recording.readerEntries(), "asof-fail count");
            assertThat(counters.getItem.get())
                    .as("count must also be a point read: %s", counters.describe())
                    .isGreaterThanOrEqualTo(1);
            assertThat(counters.query.get())
                    .as("count must not downgrade to Query: %s", counters.describe())
                    .isZero();
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    private static void assertGetItemPlan(Operation op) {
        QueryPlan plan = planner.plan(new com.gs.fw.common.mithra.finder.AnalyzedOperation(op), design);
        assertThat(plan.kind())
                .as("this case is only meaningful on GET_ITEM: %s", plan.toAssertableString())
                .isEqualTo(PlanKind.GET_ITEM);
        assertThat(plan.filterExpression())
                .as("the discarded filter must be present on the plan: %s", plan.toAssertableString())
                .isNotBlank();
    }

    private static Operation completeKey(int id, Timestamp from, Timestamp inZ, Timestamp businessAsOf) {
        return DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDateFrom().eq(from))
                .and(DiffBalanceFinder.processingDateFrom().eq(inZ))
                .and(DiffBalanceFinder.businessDate().eq(businessAsOf))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
    }

    private static Map<String, Object> balanceRow(int id, double qty, String label,
                                                  Timestamp from, Timestamp thru, Timestamp inZ) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("balanceId", Integer.valueOf(id));
        row.put("quantity", Double.valueOf(qty));
        row.put("label", label);
        row.put("note", null);
        row.put("businessDateFrom", from);
        row.put("businessDateTo", thru);
        row.put("processingDateFrom", inZ);
        row.put("processingDateTo", DiffBalanceFinder.processingDate().getInfinityDate());
        return row;
    }

    private static Timestamp utc(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(0);
        return t;
    }
}
