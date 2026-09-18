package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.PlanningPurpose;
import io.reladynamo.core.plan.PlanningRequest;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.exec.ExecutionExplain;
import io.reladynamo.ddb.exec.PageLimitExceededException;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Query-path load: DynamoDB's own 1 MB response cap, not a shrunk {@code pageSize}, must
 * force multi-page results under {@link PlannerConfig} defaults.
 *
 * <p>Shape (a) is one business key with many bitemporal versions in a single item collection.
 * Shape (b) is many partition keys answered by {@code IN} fan-out (PartiQL), never a Scan.
 * Both are seeded on H2 through Reladomo and mirrored into DynamoDB, then the same finder
 * call is compared. The closed-loop gate classifies this as query-path because the file name
 * contains {@code FinderDriven}.
 *
 * <p>{@code HOT_VERSIONS} and {@code WIDE_KEYS} stay at or below {@link PlannerConfig#DEFAULT_PAGE_SIZE}
 * so {@code Limit=100} cannot be the reason a query pages. If reducing those constants to 2
 * makes the multi-page assertion fail, the test is load-bearing.
 */
class FinderDrivenPaginationLoadTest {

    /**
     * Processing versions of one business key. Stays at or below {@link PlannerConfig#DEFAULT_PAGE_SIZE}
     * so {@code Limit=100} cannot explain paging — only DynamoDB's 1 MB cap can. Shrinking this to 2
     * makes {@link #assertMultiPageFromOneMb} fail; that run is in BUILD-LOG.txt.
     */
    private static final int HOT_VERSIONS = 80;
    /** One PartiQL IN-chunk is 50; stay at one chunk so extra requests are pages, not chunks. */
    private static final int WIDE_KEYS = 50;
    private static final int NOTE_CHARS = 24_000;

    private static final int HOT_ID = 31001;
    private static final int WIDE_FIRST_ID = 32001;
    private static final long P0 = DifferentialSupport.utc(2020, 5, 1, 9, 0, 0, 0).getTime();

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static PhysicalDesign design;
    private static ItemCodec codec;
    private static DynamoDbWriter writer;
    private static QueryPlanner planner;
    private static QueryPlanExecutor executor;
    private static DynamoDbPersister adapter;
    private static PlannerConfig defaults;
    private static MithraObjectReader jdbcReader;
    private static MithraAbstractObjectPortal portal;
    private static RelationshipDifferentialTest.RequestCounter counter;
    private static DefaultKeyStrategy keys;
    private static String padNote;
    private static int estimatedItemBytes;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        mapping = DifferentialSupport.parseMapping("/reladomo/models/DiffBalance.xml");
        codec = new ItemCodec(mapping);
        DifferentialSupport.createPkSkTable(ddb, mapping.tableName());
        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        design = PhysicalDesign.builder(mapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance())
                .build();
        planner = new QueryPlanner();
        defaults = PlannerConfig.builder().build();
        counter = new RelationshipDifferentialTest.RequestCounter();
        executor = new QueryPlanExecutor(counter.wrap(ddb.client()), codec);
        adapter = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping, writer,
                planner, executor, design, defaults);
        portal = (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();
        jdbcReader = portal.getDatabaseObject();
        keys = new DefaultKeyStrategy();
        padNote = pad(NOTE_CHARS);

        assertThat(defaults.pageSize()).isEqualTo(PlannerConfig.DEFAULT_PAGE_SIZE);
        assertThat(defaults.maxPages()).isEqualTo(PlannerConfig.DEFAULT_MAX_PAGES);
        assertThat(HOT_VERSIONS)
                .as("HOT_VERSIONS must stay <= default pageSize or Limit, not 1 MB, would page")
                .isLessThanOrEqualTo(PlannerConfig.DEFAULT_PAGE_SIZE);
        assertThat(WIDE_KEYS)
                .as("WIDE_KEYS must stay <= default pageSize and the PartiQL IN chunk of 50")
                .isLessThanOrEqualTo(50);

        seedHotPartition();
        seedWideTable();
        estimatedItemBytes = estimateItemBytes(HOT_ID);
        System.out.println("LOAD_SEED hotVersions=" + HOT_VERSIONS
                + " wideKeys=" + WIDE_KEYS
                + " noteChars=" + NOTE_CHARS
                + " estimatedItemBytes=" + estimatedItemBytes
                + " pageSize=" + defaults.pageSize()
                + " maxPages=" + defaults.maxPages());
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
    void hot_partition_finder_agrees_and_spans_dynamo_one_mb_pages() {
        Operation op = edgePointOf(HOT_ID);
        QueryPlan plan = plan(op, defaults);
        assertThat(plan.kind()).as(plan.toAssertableString()).isEqualTo(PlanKind.QUERY);
        assertDefaultPagingKnobs(plan);

        long started = System.nanoTime();
        List<Map<String, Object>> fromH2 = runFinder(op);
        int h2Count = fromH2.size();
        assertThat(h2Count)
                .as("H2 must return every processing version of the hot key")
                .isEqualTo(HOT_VERSIONS);

        counter.reset();
        List<Map<String, Object>> fromDynamo;
        int dynamoCount;
        portal.setMithraObjectReader(adapter);
        try {
            fromDynamo = runFinder(op);
            dynamoCount = adapter.count(op);
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ExecutionExplain explain = executor.lastExplain();
        System.out.println("LOAD_HOT rowsH2=" + h2Count
                + " rowsDdb=" + fromDynamo.size()
                + " countDdb=" + dynamoCount
                + " pages=" + explain.pageCount()
                + " requests=" + explain.requestCount()
                + " examined=" + explain.actualItemsExamined()
                + " counter=" + counter.describe()
                + " elapsedMs=" + elapsedMs
                + " estimatedItemBytes=" + estimatedItemBytes);

        assertFinderAgrees(fromH2, fromDynamo);
        assertThat(dynamoCount).isEqualTo(h2Count);
        assertThat(counter.describe()).contains("scan=0");
        assertMultiPageFromOneMb(plan, explain, h2Count, "hot-partition");
    }

    @Test
    void wide_table_in_fan_out_agrees_and_spans_dynamo_one_mb_pages() {
        Operation op = wideEdgePoint();
        QueryPlan plan = plan(op, defaults);
        assertThat(plan.kind())
                .as("wide table must use IN fan-out, not a Scan: %s", plan.toAssertableString())
                .isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertDefaultPagingKnobs(plan);

        long started = System.nanoTime();
        List<Map<String, Object>> fromH2 = runFinder(op);
        int h2Count = fromH2.size();
        assertThat(h2Count)
                .as("H2 must return one current version per wide-table key")
                .isEqualTo(WIDE_KEYS);

        counter.reset();
        List<Map<String, Object>> fromDynamo;
        int dynamoCount;
        portal.setMithraObjectReader(adapter);
        try {
            fromDynamo = runFinder(op);
            dynamoCount = adapter.count(op);
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        ExecutionExplain explain = executor.lastExplain();
        System.out.println("LOAD_WIDE rowsH2=" + h2Count
                + " rowsDdb=" + fromDynamo.size()
                + " countDdb=" + dynamoCount
                + " pages=" + explain.pageCount()
                + " requests=" + explain.requestCount()
                + " examined=" + explain.actualItemsExamined()
                + " counter=" + counter.describe()
                + " elapsedMs=" + elapsedMs
                + " estimatedItemBytes=" + estimatedItemBytes);

        assertFinderAgrees(fromH2, fromDynamo);
        assertThat(dynamoCount).isEqualTo(h2Count);
        assertThat(counter.describe()).contains("scan=0");
        assertMultiPageFromOneMb(plan, explain, h2Count, "wide-table");
    }

    @Test
    void maxPages_refuses_when_one_mb_paging_needs_more_pages_than_allowed() {
        Operation op = edgePointOf(HOT_ID);
        PlannerConfig tight = PlannerConfig.builder().maxPages(1).build();
        assertThat(tight.pageSize())
                .as("volume-time maxPages must keep the default pageSize; shrinking it is the other test")
                .isEqualTo(PlannerConfig.DEFAULT_PAGE_SIZE);
        QueryPlan plan = plan(op, tight);
        assertThat(plan.maxPages()).isEqualTo(1);
        assertThat(plan.pageSize()).isEqualTo(PlannerConfig.DEFAULT_PAGE_SIZE);

        DynamoDbPersister tightAdapter = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping, writer,
                planner, executor, design, tight);

        List<Map<String, Object>> fromH2 = runFinder(op);
        assertThat(fromH2).isNotEmpty();

        portal.setMithraObjectReader(tightAdapter);
        try {
            try {
                List<Map<String, Object>> truncated = runFinder(op);
                fail("maxPages=1 returned " + truncated.size()
                        + " row(s) instead of refusing; H2 has " + fromH2.size()
                        + ". Silent truncation is a defect.");
            } catch (Throwable thrown) {
                PageLimitExceededException refusal =
                        RelationshipFixture.causeOfType(thrown, PageLimitExceededException.class);
                if (refusal == null) {
                    throw thrown;
                }
                assertThat(refusal.getMessage()).contains("RELADYNAMO-PLAN-006");
                assertThat(refusal.getMessage()).contains("maxPages=1");
                System.out.println("LOAD_MAXPAGES refusal=" + refusal.getMessage());
            }

            assertThatThrownBy(() -> tightAdapter.count(op))
                    .as("count must refuse too; a partial total is indistinguishable from a small table")
                    .satisfies(thrown -> assertThat(
                            RelationshipFixture.causeOfType(thrown, PageLimitExceededException.class))
                            .as("count threw %s", thrown)
                            .isNotNull()
                            .extracting(Throwable::getMessage)
                            .asString()
                            .contains("RELADYNAMO-PLAN-006"));
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    // --- seed --------------------------------------------------------------------

    private static void seedHotPartition() {
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance b = new DiffBalance(DifferentialSupport.utc(2026, 1, 1));
            b.setBalanceId(HOT_ID);
            b.setQuantity(1.0d);
            b.setLabel("hot");
            b.setNote(padNote);
            b.insert();
            return null;
        });
        for (int i = 1; i < HOT_VERSIONS; i++) {
            final int version = i;
            DifferentialSupport.inTransaction(P0 + version * 1000L, tx -> {
                // As-of the original FROM, not a later business date: a mid-range as-of would
                // split the business rectangle and produce more versions than HOT_VERSIONS.
                DiffBalance current = DiffBalanceFinder.findOne(
                        DiffBalanceFinder.balanceId().eq(HOT_ID)
                                .and(DiffBalanceFinder.businessDate().eq(
                                        DifferentialSupport.utc(2026, 1, 1)))
                                .and(DiffBalanceFinder.processingDate().eq(
                                        DiffBalanceFinder.processingDate().getInfinityDate())));
                if (current == null) {
                    throw new IllegalStateException("hot partition seed missing at version " + version);
                }
                current.setQuantity(1.0d + version);
                return null;
            });
        }
        mirrorRange(HOT_ID, HOT_ID);
    }

    private static void seedWideTable() {
        DifferentialSupport.inTransaction(P0 + 500_000L, tx -> {
            for (int i = 0; i < WIDE_KEYS; i++) {
                DiffBalance b = new DiffBalance(DifferentialSupport.utc(2026, 1, 1));
                b.setBalanceId(WIDE_FIRST_ID + i);
                b.setQuantity(1.0d + i);
                b.setLabel("wide");
                b.setNote(padNote);
                b.insert();
            }
            return null;
        });
        mirrorRange(WIDE_FIRST_ID, WIDE_FIRST_ID + WIDE_KEYS - 1);
    }

    private static void mirrorRange(int fromId, int toId) {
        DiffBalanceList list = DiffBalanceFinder.findMany(
                DiffBalanceFinder.balanceId().greaterThanEquals(fromId)
                        .and(DiffBalanceFinder.balanceId().lessThanEquals(toId))
                        .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                        .and(DiffBalanceFinder.processingDate().equalsEdgePoint()));
        list.setBypassCache(true);
        for (int i = 0; i < list.size(); i++) {
            writer.upsert(MithraDataAccessor.extract(
                    DiffBalanceFinder.getFinderInstance(), list.get(i).zGetCurrentData()));
        }
    }

    // --- finder / plan ------------------------------------------------------------

    private static QueryPlan plan(Operation op, PlannerConfig config) {
        return planner.plan(new PlanningRequest(
                new AnalyzedOperation(op), null, design, config, 0, 1, PlanningPurpose.FIND));
    }

    private static Operation edgePointOf(int balanceId) {
        return DiffBalanceFinder.balanceId().eq(balanceId)
                .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                .and(DiffBalanceFinder.processingDate().equalsEdgePoint());
    }

    private static Operation wideEdgePoint() {
        IntHashSet ids = new IntHashSet(WIDE_KEYS);
        for (int i = 0; i < WIDE_KEYS; i++) {
            ids.add(WIDE_FIRST_ID + i);
        }
        return DiffBalanceFinder.balanceId().in(ids)
                .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                .and(DiffBalanceFinder.processingDate().equalsEdgePoint());
    }

    private static List<Map<String, Object>> runFinder(Operation op) {
        DiffBalanceList list = DiffBalanceFinder.findMany(op);
        list.setBypassCache(true);
        list.forceResolve();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); i++) {
            rows.add(MithraDataAccessor.extract(
                    DiffBalanceFinder.getFinderInstance(), list.get(i).zGetCurrentData()));
        }
        return rows;
    }

    private static void assertFinderAgrees(List<Map<String, Object>> fromH2,
                                           List<Map<String, Object>> fromDynamo) {
        assertThat(fromH2).as("the H2 reference must return rows, or the comparison is vacuous")
                .isNotEmpty();
        RowSetDiff diff = TemporalRowSetDiffer.compare(fromH2, fromDynamo);
        assertThat(diff.isIdentical())
                .as("the same finder call disagreed between stores:%n%s", diff.describe())
                .isTrue();
    }

    private static void assertDefaultPagingKnobs(QueryPlan plan) {
        assertThat(plan.pageSize())
                .as("default PlannerConfig pageSize must be left at 100; shrinking it is PaginationSafeguardFinderTest")
                .isEqualTo(PlannerConfig.DEFAULT_PAGE_SIZE);
        assertThat(plan.maxPages()).isEqualTo(PlannerConfig.DEFAULT_MAX_PAGES);
    }

    /**
     * Multi-page because the payload exceeds DynamoDB's 1 MB cap, not because Limit was set
     * below the row count. Result rows stay &lt;= default pageSize, so Limit=100 cannot split them.
     */
    private static void assertMultiPageFromOneMb(QueryPlan plan, ExecutionExplain explain,
                                                int resultRows, String shape) {
        assertThat(resultRows)
                .as("%s: result rows must fit in one Limit=%s page, so extra pages are the 1 MB cap",
                        shape, Integer.valueOf(plan.pageSize()))
                .isLessThanOrEqualTo(plan.pageSize());
        assertThat(explain.pageCount())
                .as("%s: DynamoDB must have returned more than one page (observed requests=%s, "
                                + "examined=%s, resultRows=%s, estimatedItemBytes=%s, noteChars=%s). "
                                + "If this fails after shrinking HOT_VERSIONS/WIDE_KEYS, the assertion "
                                + "is load-bearing.",
                        shape, Integer.valueOf(explain.requestCount()),
                        Integer.valueOf(explain.actualItemsExamined()),
                        Integer.valueOf(resultRows),
                        Integer.valueOf(estimatedItemBytes),
                        Integer.valueOf(NOTE_CHARS))
                .isGreaterThan(1);
        assertThat(explain.requestCount()).isGreaterThan(1);
        assertThat(counter.reads()).isGreaterThan(1);
    }

    private static String pad(int n) {
        char[] chars = new char[n];
        java.util.Arrays.fill(chars, 'N');
        return new String(chars);
    }

    private static int estimateItemBytes(int id) {
        String pk = keys.partitionKey(mapping,
                Collections.singletonMap("balanceId", Integer.valueOf(id)));
        Map<String, AttributeValue> values = new HashMap<String, AttributeValue>();
        values.put(":pk", AttributeValue.builder().s(pk).build());
        QueryResponse response = ddb.client().query(b -> b
                .tableName(mapping.tableName())
                .keyConditionExpression("pk = :pk")
                .expressionAttributeValues(values)
                .limit(Integer.valueOf(1))
                .consistentRead(Boolean.TRUE));
        if (response.items() == null || response.items().isEmpty()) {
            return 0;
        }
        int bytes = 0;
        for (Map.Entry<String, AttributeValue> e : response.items().get(0).entrySet()) {
            bytes += e.getKey().getBytes(StandardCharsets.UTF_8).length;
            AttributeValue v = e.getValue();
            if (v.s() != null) {
                bytes += v.s().getBytes(StandardCharsets.UTF_8).length;
            }
            if (v.n() != null) {
                bytes += v.n().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return bytes;
    }
}
