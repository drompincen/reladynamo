package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.PlanningPurpose;
import io.reladynamo.core.plan.PlanningRequest;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inspection acceptance case 7: OR branches retain distinct bindings, deduplicate results,
 * and preserve ordered top-N behaviour — end to end through a generated finder.
 *
 * <p>{@link io.reladynamo.ddb.exec.FanOutSelectCollapseTest} covers collapse rules at plan
 * level. These cases require a cold-cache generated finder, codec-written rows, and
 * {@link RequestAssertions} so a Reladomo cache hit cannot masquerade as adapter success.
 */
class AcceptanceOrFanOutFinderTest {

    private static FinderMatrixHarness harness;

    @BeforeAll
    static void setUp() {
        harness = FinderMatrixHarness.boot();
        List<ValueRow> seed = FixtureManifests.coreSeed();
        harness.truncateH2();
        harness.seedH2(seed);
        harness.seedDdb(seed);
        harness.awaitGsi(1, FixtureManifests.V());
        harness.awaitGsi(6, FixtureManifests.F());
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void should_return_one_logical_row_and_count_one_when_or_branches_overlap_on_the_same_identity() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(1, 3).and(DiffFinderValueFinder.textValue().eq("beta"))
                        .or(K(1, 3).and(DiffFinderValueFinder.intValue().eq(2)));

        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult rows = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(rowIds(rows))
                .as("overlapping OR branches of the same physical identity must not double the row")
                .containsExactly(Integer.valueOf(3));
        RequestAssertions.requirePositiveDataReads(rows.counters.dataReads(), "overlapping OR findMany");
        RequestAssertions.requireReaderEntered(rows.readerEntries, "overlapping OR findMany");

        harness.matchH2(recipe, FinderShape.countFanout());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.countFanout());
        assertThat(count.count)
                .as("count() must agree with the deduplicated findMany")
                .isEqualTo(Integer.valueOf(1));
        RequestAssertions.requirePositiveDataReads(count.counters.dataReads(), "overlapping OR count");
    }

    @Test
    void should_return_the_true_maximum_when_it_lives_in_the_last_or_partition() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(1, 1).or(K(1, 2)).or(K(1, 3)).or(K(1, 4)).or(K(1, 7));

        QueryPlan plan = new QueryPlanner().plan(new PlanningRequest(
                new AnalyzedOperation(recipe.create()),
                DiffFinderValueFinder.intValue().descendingOrderBy(),
                harness.design,
                PlannerConfig.defaults(),
                1,
                1,
                PlanningPurpose.FIND));
        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        List<QueryPlan> children = plan.fanOut();
        assertThat(children.size()).isGreaterThanOrEqualTo(2);
        String lastPk = children.get(children.size() - 1).keyCondition().encodedPartitionKey();
        assertThat(lastPk)
                .as("the true maximum (row 7, intValue=100) must be the last fan-out partition; "
                        + "early limiting of the first partition would silently return the wrong row")
                .endsWith("#7");
        assertThat(children.get(0).keyCondition().encodedPartitionKey())
                .as("first partition is a smaller intValue; taking top-1 from it would be wrong")
                .doesNotEndWith("#7");

        FinderShape shape = FinderShape.findManyOrdered(
                DiffFinderValueFinder.intValue().descendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                Integer.valueOf(1),
                FinderShape.Route.EXECUTE_OR_FANOUT);
        harness.matchH2(recipe, shape);
        InvocationResult ddb = harness.run(FinderMatrixHarness.Backend.DDB, recipe, shape);
        assertThat(rowIds(ddb))
                .as("descending top-one must wait for every partition; row 7 is the maximum")
                .containsExactly(Integer.valueOf(7));
        RequestAssertions.requirePositiveDataReads(ddb.counters.dataReads(), "last-partition top-one");
        RequestAssertions.requireReaderEntered(ddb.readerEntries, "last-partition top-one");
    }

    @Test
    void should_return_both_rows_when_or_branches_differ_only_in_literal_value() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(6, 1).and(DiffFinderValueFinder.textValue().eq("A"))
                        .or(K(6, 2).and(DiffFinderValueFinder.textValue().eq("B")));

        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(rowIds(ddb))
                .as("(scope=6,row=1,text=A) OR (scope=6,row=2,text=B) must keep both rows; "
                        + "collapsing to a shared text=A binding loses row 2")
                .containsExactlyInAnyOrder(Integer.valueOf(1), Integer.valueOf(2));
        RequestAssertions.requirePositiveDataReads(ddb.counters.dataReads(), "literal-distinct OR");
        RequestAssertions.requireReaderEntered(ddb.readerEntries, "literal-distinct OR");
        RequestAssertions.requireZeroScan(ddb.counters.scan.get(), "literal-distinct OR");
    }

    private static Operation K(int scopeId, int rowId) {
        return DiffFinderValueFinder.scopeId().eq(scopeId)
                .and(DiffFinderValueFinder.rowId().eq(rowId));
    }

    private static List<Integer> rowIds(InvocationResult result) {
        List<Integer> ids = new ArrayList<Integer>();
        for (int i = 0; i < result.rows.size(); i++) {
            ids.add(Integer.valueOf(result.rows.get(i).rowId()));
        }
        return ids;
    }
}
