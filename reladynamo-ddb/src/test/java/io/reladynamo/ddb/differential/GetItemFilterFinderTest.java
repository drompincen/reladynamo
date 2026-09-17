package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import com.gs.fw.common.mithra.querycache.CachedQuery;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-03 through a generated Reladomo finder on a bound portal, not a hand-built plan.
 *
 * <p>Rows are codec-written. The planner emits {@code GET_ITEM} for a complete dated key
 * (balanceId + both from-column equalities) while still attaching payload / as-of filters.
 */
class GetItemFilterFinderTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static DynamoDbWriter writer;
    private static DynamoDbPersister adapter;
    private static QueryPlanner planner;
    private static PhysicalDesign design;
    private static MithraObjectReader jdbcReader;
    private static MithraAbstractObjectPortal portal;

    @BeforeAll
    static void setUp() throws Exception {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();

        StringBuilder xml = new StringBuilder();
        try (InputStream in = GetItemFilterFinderTest.class
                .getResourceAsStream("/reladomo/models/DiffBalance.xml");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                xml.append(line).append('\n');
            }
        }
        mapping = new MithraObjectXmlParser().parse(xml.toString());
        ItemCodec codec = new ItemCodec(mapping);
        DifferentialSupport.createPkSkTable(ddb, mapping.tableName());

        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        planner = new QueryPlanner();
        design = PhysicalDesign.builder(mapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance())
                .build();
        adapter = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping, writer,
                planner, new QueryPlanExecutor(ddb.client(), codec),
                design, PlannerConfig.builder().build());

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
    void should_find_nothing_and_count_zero_when_complete_key_payload_filter_fails() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 12, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int id = 9301;
        writer.insert(balanceRow(id, 1.0d, "CLOSED", from, thru, inZ));

        Operation op = completeKey(id, from, inZ, utc(2026, 6, 1))
                .and(DiffBalanceFinder.label().eq("ACTIVE"));
        assertGetItemWithFilter(op);

        portal.setMithraObjectReader(adapter);
        try {
            assertThat(finderRows(op))
                    .as("complete PK + label=ACTIVE must not return CLOSED")
                    .isEmpty();
            assertThat(adapter.count(op)).isZero();
            CachedQuery found = adapter.find(new AnalyzedOperation(op), null, false, 0, 1, true, false);
            assertThat(found.getResult()).isEmpty();
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    @Test
    void should_return_the_row_when_complete_key_payload_filter_passes() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 12, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int id = 9302;
        writer.insert(balanceRow(id, 2.0d, "ACTIVE", from, thru, inZ));

        Operation op = completeKey(id, from, inZ, utc(2026, 6, 1))
                .and(DiffBalanceFinder.label().eq("ACTIVE"));
        assertGetItemWithFilter(op);

        portal.setMithraObjectReader(adapter);
        try {
            List<?> rows = finderRows(op);
            assertThat(rows).hasSize(1);
            assertThat(adapter.count(op)).isEqualTo(1);
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    @Test
    void should_find_nothing_when_exact_dated_rectangle_fails_as_of_containment() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 6, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int id = 9303;
        writer.insert(balanceRow(id, 3.0d, "OPEN", from, thru, inZ));

        Operation op = completeKey(id, from, inZ, utc(2026, 7, 1));
        assertGetItemWithFilter(op);

        portal.setMithraObjectReader(adapter);
        try {
            assertThat(finderRows(op))
                    .as("as-of July 1 is outside [Jan 1, June 1)")
                    .isEmpty();
            assertThat(adapter.count(op)).isZero();
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    private static void assertGetItemWithFilter(Operation op) {
        QueryPlan plan = planner.plan(new AnalyzedOperation(op), design);
        assertThat(plan.kind())
                .as("this test is only meaningful on GET_ITEM: %s", plan.toAssertableString())
                .isEqualTo(PlanKind.GET_ITEM);
        assertThat(plan.filterExpression())
                .as("the discarded filter must actually be present on the plan: %s",
                        plan.toAssertableString())
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

    private static List<?> finderRows(Operation op) {
        DiffBalanceList list = DiffBalanceFinder.findMany(op);
        list.setBypassCache(true);
        list.forceResolve();
        return list;
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
