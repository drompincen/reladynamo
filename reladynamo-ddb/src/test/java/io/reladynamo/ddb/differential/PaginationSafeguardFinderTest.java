package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.All;
import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.PlanningPurpose;
import io.reladynamo.core.plan.PlanningRequest;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.core.plan.ReladynamoUnplannableOperationException;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.exec.PageLimitExceededException;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R-09 through a generated finder, the planner, and the executor — not a hand-built plan.
 *
 * <p>Limits are set on {@link PlannerConfig}. Exceeding them must throw; a truncated list
 * presented as complete is the failure this finding exists to prevent.
 */
class PaginationSafeguardFinderTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static PhysicalDesign design;
    private static ItemCodec codec;
    private static DynamoDbWriter writer;
    private static QueryPlanner planner;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        mapping = DifferentialSupport.parseMapping("/reladomo/models/DiffBalance.xml");
        codec = new ItemCodec(mapping);
        DifferentialSupport.createPkSkTable(ddb, mapping.tableName());
        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new io.reladynamo.core.key.DefaultKeyStrategy());
        design = PhysicalDesign.builder(mapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance())
                .build();
        planner = new QueryPlanner();
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void should_refuse_when_a_finder_query_exceeds_configured_maxPages() {
        int id = 19001;
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        insertMatchingVersions(id, 5, asOf);

        PlannerConfig config = PlannerConfig.builder()
                .maxPages(2)
                .pageSize(1)
                .build();
        Operation op = currentOf(id, asOf);
        QueryPlan plan = plan(op, config);

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.maxPages()).isEqualTo(2);
        assertThat(plan.pageSize()).isEqualTo(1);

        assertThatThrownBy(() -> new QueryPlanExecutor(ddb.client(), codec).execute(plan))
                .isInstanceOf(PageLimitExceededException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-006")
                .hasMessageContaining("maxPages=2");
    }

    @Test
    void should_refuse_when_a_finder_scan_exceeds_configured_maxPages() {
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        for (int i = 0; i < 5; i++) {
            insertMatchingVersions(19100 + i, 1, asOf);
        }

        PlannerConfig config = PlannerConfig.builder()
                .allowTableScan(true)
                .parallelScanSegments(1)
                .maxPages(2)
                .pageSize(1)
                .build();
        Operation op = new All(DiffBalanceFinder.balanceId())
                .and(DiffBalanceFinder.businessDate().eq(asOf))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
        QueryPlan plan = plan(op, config);

        assertThat(plan.kind()).isEqualTo(PlanKind.SCAN);
        assertThat(plan.maxPages()).isEqualTo(2);

        assertThatThrownBy(() -> new QueryPlanExecutor(ddb.client(), codec).execute(plan))
                .isInstanceOf(PageLimitExceededException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-006")
                .hasMessageContaining("maxPages=2");
    }

    @Test
    void should_refuse_when_a_finder_partiql_fan_out_exceeds_configured_maxPages() {
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        IntHashSet ids = new IntHashSet();
        for (int i = 0; i < 5; i++) {
            int id = 19200 + i;
            ids.add(id);
            insertMatchingVersions(id, 1, asOf);
        }

        PlannerConfig config = PlannerConfig.builder()
                .maxPages(2)
                .pageSize(1)
                .build();
        Operation op = DiffBalanceFinder.balanceId().in(ids)
                .and(DiffBalanceFinder.businessDate().eq(asOf))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
        QueryPlan plan = plan(op, config);

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(plan.maxPages()).isEqualTo(2);

        assertThatThrownBy(() -> new QueryPlanExecutor(ddb.client(), codec).execute(plan))
                .isInstanceOf(PageLimitExceededException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-006")
                .hasMessageContaining("maxPages=2");
    }

    @Test
    void should_refuse_when_a_finder_partiql_page_limit_is_hit_inside_one_statement() {
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        IntHashSet ids = new IntHashSet();
        ids.add(19600);
        ids.add(19601);
        insertMatchingVersions(19600, 5, asOf);
        insertMatchingVersions(19601, 5, asOf);

        PlannerConfig config = PlannerConfig.builder()
                .maxPages(2)
                .pageSize(2)
                .build();
        Operation op = DiffBalanceFinder.balanceId().in(ids)
                .and(DiffBalanceFinder.businessDate().eq(asOf))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
        QueryPlan plan = plan(op, config);

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(plan.maxPages()).isEqualTo(2);
        assertThat(plan.pageSize()).isEqualTo(2);

        assertThatThrownBy(() -> new QueryPlanExecutor(ddb.client(), codec).execute(plan))
                .as("PartiQL ExecuteStatement must honour pageSize as Limit so maxPages is reachable "
                        + "inside one IN-list; chunking two partition keys into one statement is not "
                        + "a bound on items per page")
                .isInstanceOf(PageLimitExceededException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-006")
                .hasMessageContaining("maxPages=2");
    }

    @Test
    void should_refuse_when_partiql_accumulation_exceeds_configured_inMemoryRowCeiling() {
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        IntHashSet ids = new IntHashSet();
        ids.add(19700);
        ids.add(19701);
        insertMatchingVersions(19700, 3, asOf);
        insertMatchingVersions(19701, 3, asOf);

        PlannerConfig config = PlannerConfig.builder()
                .inMemoryRowCeiling(3)
                .maxPages(64)
                .build();
        Operation op = DiffBalanceFinder.balanceId().in(ids)
                .and(DiffBalanceFinder.businessDate().eq(asOf))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
        QueryPlan plan = plan(op, config);

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThatThrownBy(() -> new QueryPlanExecutor(ddb.client(), codec).execute(plan))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-007")
                .hasMessageContaining("inMemoryRowCeiling=3");
    }

    @Test
    void should_refuse_when_accumulation_exceeds_configured_inMemoryRowCeiling() {
        int id = 19300;
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        insertMatchingVersions(id, 5, asOf);

        PlannerConfig config = PlannerConfig.builder()
                .inMemoryRowCeiling(2)
                .maxPages(64)
                .build();
        QueryPlan plan = plan(currentOf(id, asOf), config);

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThatThrownBy(() -> new QueryPlanExecutor(ddb.client(), codec).execute(plan))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-007")
                .hasMessageContaining("inMemoryRowCeiling=2");
    }

    @Test
    void should_refuse_count_when_the_row_ceiling_is_exceeded_rather_than_returning_a_partial_total() {
        int id = 19400;
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        insertMatchingVersions(id, 5, asOf);

        PlannerConfig config = PlannerConfig.builder()
                .inMemoryRowCeiling(2)
                .build();
        DynamoDbPersister persister = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping, writer,
                planner, new QueryPlanExecutor(ddb.client(), codec),
                design, config);

        assertThatThrownBy(() -> persister.count(currentOf(id, asOf)))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-007")
                .hasMessageContaining("inMemoryRowCeiling=2");
    }

    @Test
    void should_refuse_when_in_memory_order_would_need_more_pages_than_maxPages() {
        int id = 19500;
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        insertMatchingVersions(id, 5, asOf);

        PlannerConfig config = PlannerConfig.builder()
                .maxPages(2)
                .pageSize(1)
                .inMemoryRowCeiling(50_000)
                .build();
        QueryPlan plan = planner.plan(new PlanningRequest(
                new AnalyzedOperation(currentOf(id, asOf)),
                DiffBalanceFinder.quantity().descendingOrderBy(),
                design, config, 1, 1, PlanningPurpose.FIND));

        assertThat(plan.orderMode()).isEqualTo(io.reladynamo.core.plan.OrderMode.IN_MEMORY);
        assertThat(plan.dynamoLimit())
                .as("R-08: in-memory order must not use rowcount as Dynamo Limit")
                .isNull();

        assertThatThrownBy(() -> new QueryPlanExecutor(ddb.client(), codec).execute(plan))
                .as("a page cap that would clip the set before sort must refuse, not return a partial top-N")
                .isInstanceOf(PageLimitExceededException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-006");
    }

    private static QueryPlan plan(Operation op, PlannerConfig config) {
        return planner.plan(new PlanningRequest(
                new AnalyzedOperation(op), null, design, config, 0, 1, PlanningPurpose.FIND));
    }

    private static Operation currentOf(int id, Timestamp asOf) {
        return DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().eq(asOf))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
    }

    /**
     * Several current rectangles of one logical key, all covering {@code asOf}. Distinct
     * processing-from timestamps give distinct sort keys so a Query of the partition returns
     * every version.
     */
    private static void insertMatchingVersions(int balanceId, int versions, Timestamp asOf) {
        Timestamp from = DifferentialSupport.utc(2026, 1, 1);
        Timestamp thru = DifferentialSupport.utc(2026, 12, 1);
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        for (int i = 0; i < versions; i++) {
            Timestamp inZ = DifferentialSupport.utc(2026, 1, 2 + i);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("balanceId", Integer.valueOf(balanceId));
            row.put("quantity", Double.valueOf(1.0d + i));
            row.put("label", "OPEN");
            row.put("note", "v" + i);
            row.put("businessDateFrom", from);
            row.put("businessDateTo", thru);
            row.put("processingDateFrom", inZ);
            row.put("processingDateTo", inf);
            writer.insert(row);
        }
    }
}
