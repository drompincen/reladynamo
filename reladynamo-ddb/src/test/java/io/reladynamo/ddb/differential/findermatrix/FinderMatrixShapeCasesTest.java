package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FINDER-MATRIX.md §6.3 boolean structure and result-shape cases. Cold-cache paired
 * runner; H2 is the oracle. Point-read / OR-binding / top-N cases already in
 * {@link FinderMatrixMinimumCasesTest} are not repeated.
 */
class FinderMatrixShapeCasesTest {

    private static FinderMatrixHarness harness;

    @BeforeAll
    static void setUp() {
        harness = FinderMatrixHarness.boot();
        List<ValueRow> seed = new ArrayList<ValueRow>();
        seed.addAll(FixtureManifests.coreSeed());
        seed.addAll(FixtureManifests.O());
        harness.truncateH2();
        harness.seedH2(seed);
        harness.seedDdb(seed);
        harness.awaitGsi(1, FixtureManifests.V());
        harness.awaitGsi(2, FixtureManifests.X());
        harness.awaitGsi(5, FixtureManifests.O());
        harness.awaitGsi(6, FixtureManifests.F());
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    // --- boolean structure -------------------------------------------------------

    @Test
    void should_match_h2_for_and_with_residual() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.intValue().greaterThan(0))
                        .and(DiffFinderValueFinder.textValue().endsWith("beta"));
        harness.matchH2(recipe, FinderShape.findManyQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(Integer.valueOf(3), Integer.valueOf(6));
    }

    @Test
    void should_match_h2_for_or_between_pushed_and_residual() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.intValue().eq(0)
                        .or(DiffFinderValueFinder.textValue().endsWith("beta")));
        harness.matchH2(recipe, FinderShape.findManyQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(
                Integer.valueOf(2), Integer.valueOf(3), Integer.valueOf(6));
    }

    @Test
    void should_match_h2_for_nested_boolean_structure() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.intValue().eq(0)
                        .or(DiffFinderValueFinder.textValue().endsWith("beta")
                                .and(DiffFinderValueFinder.booleanValue().eq(true))));
        harness.matchH2(recipe, FinderShape.findManyQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(
                Integer.valueOf(2), Integer.valueOf(3), Integer.valueOf(6));
    }

    @Test
    void should_match_h2_for_distributed_dnf() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.intValue().eq(0)
                        .or(DiffFinderValueFinder.intValue().eq(2)))
                        .and(DiffFinderValueFinder.textValue().eq("")
                                .or(DiffFinderValueFinder.textValue().endsWith("beta")));
        harness.matchH2(recipe, FinderShape.findManyQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(
                Integer.valueOf(2), Integer.valueOf(3), Integer.valueOf(6));
        assertThat(ddb.counters.dataReads()).isGreaterThan(0);
    }

    @Test
    void should_match_h2_for_demorgan_negation() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.intValue().notEq(2))
                        .and(DiffFinderValueFinder.textValue().notEndsWith("beta"));
        harness.matchH2(recipe, FinderShape.findManyQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(
                Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(4), Integer.valueOf(7));
        assertThat(rowIds(ddb)).doesNotContain(Integer.valueOf(5));
    }

    @Test
    void should_not_admit_crossed_or_value_bindings() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(6, 1).and(DiffFinderValueFinder.textValue().eq("B"))
                        .or(K(6, 2).and(DiffFinderValueFinder.textValue().eq("A")));
        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(ddb.rows).isEmpty();
        assertThat(ddb.counters.dataReads()).isGreaterThan(0);
    }

    @Test
    void should_preserve_or_attribute_bindings() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(6, 1).and(DiffFinderValueFinder.intValue().eq(1))
                        .or(K(6, 2).and(DiffFinderValueFinder.textValue().eq("B")));
        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(Integer.valueOf(1), Integer.valueOf(2));
        assertThat(ddb.rows.get(0).scopeId()).isEqualTo(6);
    }

    @Test
    void should_preserve_or_binary_bindings() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(1, 1).and(DiffFinderValueFinder.bytesValue().eq(FixtureManifests.hex(0x00, 0xFF)))
                        .or(K(1, 3).and(DiffFinderValueFinder.bytesValue().eq(FixtureManifests.hex(0x01, 0x02))));
        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(Integer.valueOf(1), Integer.valueOf(3));
    }

    @Test
    void should_preserve_distinct_or_residuals() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(1, 3).and(DiffFinderValueFinder.textValue().endsWith("beta"))
                        .or(K(1, 4).and(DiffFinderValueFinder.textValue().endsWith("bet")));
        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(Integer.valueOf(3), Integer.valueOf(4));
    }

    @Test
    void should_execute_compatible_fanout_through_partiql() {
        IntHashSet ids = new IntHashSet();
        ids.add(1);
        ids.add(2);
        ids.add(3);
        ids.add(4);
        FinderMatrixHarness.OperationRecipe recipe = () ->
                DiffFinderValueFinder.scopeId().eq(6)
                        .and(DiffFinderValueFinder.rowId().in(ids))
                        .and(DiffFinderValueFinder.intValue().greaterThan(0));
        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(
                Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3), Integer.valueOf(4));
        assertThat(ddb.rows).hasSize(4);
        assertThat(ddb.counters.executeStatement.get())
                .as("compatible collapse must observe ExecuteStatement: %s", ddb.counters.describe())
                .isGreaterThanOrEqualTo(1);
        assertThat(ddb.counters.scan.get()).isZero();
    }

    @Test
    void should_deduplicate_overlapping_or_results() {
        FinderMatrixHarness.OperationRecipe recipe = overlappingOr();
        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(
                Integer.valueOf(3), Integer.valueOf(4), Integer.valueOf(6), Integer.valueOf(7));
        harness.matchH2(recipe, FinderShape.countFanout());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.countFanout());
        assertThat(count.count).isEqualTo(Integer.valueOf(4));
    }

    @Test
    void should_preserve_null_logic_inside_or() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.intValue().isNull()
                        .or(DiffFinderValueFinder.textValue().endsWith("beta")));
        harness.matchH2(recipe, FinderShape.findManyQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(
                Integer.valueOf(3), Integer.valueOf(5), Integer.valueOf(6));
        harness.matchH2(recipe, FinderShape.countQuery());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.countQuery());
        assertThat(count.count).isEqualTo(Integer.valueOf(3));
    }

    // --- result shape: findOne / findMany / count / order / max -----------------

    @Test
    void should_accept_payload_match_on_point_read() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(1, 3).and(DiffFinderValueFinder.textValue().eq("beta"));
        harness.matchH2(recipe, FinderShape.findOneGetItem());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findOneGetItem());
        assertThat(ddb.rows).hasSize(1);
        assertThat(ddb.rows.get(0)).isEqualTo(TypedSnapshot.ofManifest(FixtureManifests.v(3)));
    }

    @Test
    void should_match_no_row_for_absent_complete_key() {
        FinderMatrixHarness.OperationRecipe recipe = () -> K(1, 999);
        harness.matchH2(recipe, FinderShape.findOneGetItem());
        InvocationResult one = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findOneGetItem());
        assertThat(one.rows).isEmpty();
        assertThat(one.counters.getItem.get()).isGreaterThanOrEqualTo(1);

        harness.matchH2(recipe, FinderShape.countGetItem());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.countGetItem());
        assertThat(count.count).isZero();
        assertThat(count.counters.dataReads()).isGreaterThan(0);
    }

    @Test
    void should_keep_both_composite_key_components() {
        FinderMatrixHarness.OperationRecipe recipe = () -> K(1, 3).or(K(2, 3));
        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(ddb.rows).hasSize(2);
        assertThat(ddb.rows).contains(TypedSnapshot.ofManifest(FixtureManifests.v(3)));
        assertThat(ddb.rows).contains(TypedSnapshot.ofManifest(FixtureManifests.X().get(0)));
        harness.matchH2(recipe, FinderShape.countFanout());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.countFanout());
        assertThat(count.count).isEqualTo(Integer.valueOf(2));
    }

    @Test
    void should_count_residual_matches_through_finder() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.doubleValue().greaterThan(2.5d))
                        .and(DiffFinderValueFinder.textValue().endsWith("z"));
        harness.matchH2(recipe, FinderShape.countQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.countQuery());
        assertThat(ddb.count).isEqualTo(Integer.valueOf(1));
        assertThat(ddb.counters.query.get()).isGreaterThanOrEqualTo(1);
        assertThat(ddb.readerEntries).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_count_null_matches_through_finder() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.booleanValue().isNull());
        harness.matchH2(recipe, FinderShape.countQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.countQuery());
        assertThat(ddb.count).isEqualTo(Integer.valueOf(1));
        assertThat(ddb.counters.query.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_order_ascending_and_descending_with_ties() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.intValue().isNotNull());
        FinderShape asc = FinderShape.findManyOrdered(
                DiffFinderValueFinder.intValue().ascendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                null,
                FinderShape.Route.QUERY_GSI);
        harness.matchH2(recipe, asc);
        InvocationResult ascDdb = harness.run(FinderMatrixHarness.Backend.DDB, recipe, asc);
        assertThat(rowIds(ascDdb)).containsExactly(
                Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3),
                Integer.valueOf(6), Integer.valueOf(4), Integer.valueOf(7));

        FinderShape desc = FinderShape.findManyOrdered(
                DiffFinderValueFinder.intValue().descendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                null,
                FinderShape.Route.QUERY_GSI);
        harness.matchH2(recipe, desc);
        InvocationResult descDdb = harness.run(FinderMatrixHarness.Backend.DDB, recipe, desc);
        assertThat(rowIds(descDdb)).containsExactly(
                Integer.valueOf(7), Integer.valueOf(4), Integer.valueOf(3),
                Integer.valueOf(6), Integer.valueOf(2), Integer.valueOf(1));
    }

    @Test
    void should_order_decimals_without_double_rounding() {
        FinderMatrixHarness.OperationRecipe recipe = () -> Q(5);
        FinderShape ascMax1 = FinderShape.findManyOrdered(
                DiffFinderValueFinder.decimalValue().ascendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                Integer.valueOf(1),
                FinderShape.Route.QUERY_GSI);
        harness.matchH2(recipe, ascMax1);
        InvocationResult asc1 = harness.run(FinderMatrixHarness.Backend.DDB, recipe, ascMax1);
        assertThat(rowIds(asc1)).containsExactly(Integer.valueOf(2));

        FinderShape descMax1 = FinderShape.findManyOrdered(
                DiffFinderValueFinder.decimalValue().descendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                Integer.valueOf(1),
                FinderShape.Route.QUERY_GSI);
        harness.matchH2(recipe, descMax1);
        InvocationResult desc1 = harness.run(FinderMatrixHarness.Backend.DDB, recipe, descMax1);
        assertThat(rowIds(desc1)).containsExactly(Integer.valueOf(3));

        FinderShape unboundedAsc = FinderShape.findManyOrdered(
                DiffFinderValueFinder.decimalValue().ascendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                null,
                FinderShape.Route.QUERY_GSI);
        harness.matchH2(recipe, unboundedAsc);
        InvocationResult unbounded = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, unboundedAsc);
        assertThat(rowIds(unbounded)).containsExactly(
                Integer.valueOf(2), Integer.valueOf(1), Integer.valueOf(3));
    }

    @Test
    void should_order_long_values_without_double_rounding() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.longValue().isNotNull());
        FinderShape shape = FinderShape.findManyOrdered(
                DiffFinderValueFinder.longValue().descendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().descendingOrderBy()),
                Integer.valueOf(3),
                FinderShape.Route.QUERY_GSI);
        harness.matchH2(recipe, shape);
        InvocationResult ddb = harness.run(FinderMatrixHarness.Backend.DDB, recipe, shape);
        assertThat(rowIds(ddb)).containsExactly(
                Integer.valueOf(7), Integer.valueOf(4), Integer.valueOf(6));
    }

    @Test
    void should_order_binary_values_by_content() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.bytesValue().isNotNull());
        FinderShape asc = FinderShape.findManyOrdered(
                DiffFinderValueFinder.bytesValue().ascendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                null,
                FinderShape.Route.QUERY_GSI);
        harness.matchH2(recipe, asc);
        InvocationResult ascDdb = harness.run(FinderMatrixHarness.Backend.DDB, recipe, asc);
        assertThat(ascDdb.rows).hasSize(6);

        FinderShape desc = FinderShape.findManyOrdered(
                DiffFinderValueFinder.bytesValue().descendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                null,
                FinderShape.Route.QUERY_GSI);
        harness.matchH2(recipe, desc);
        InvocationResult descDdb = harness.run(FinderMatrixHarness.Backend.DDB, recipe, desc);
        assertThat(descDdb.rows).hasSize(6);
        assertThat(rowIds(descDdb)).isNotEqualTo(rowIds(ascDdb));
    }

    @Test
    void should_apply_limit_after_deduplication() {
        FinderMatrixHarness.OperationRecipe recipe = overlappingOr();
        FinderShape shape = FinderShape.findManyOrdered(
                DiffFinderValueFinder.intValue().descendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                Integer.valueOf(3),
                FinderShape.Route.EXECUTE_OR_FANOUT);
        harness.matchH2(recipe, shape);
        InvocationResult ddb = harness.run(FinderMatrixHarness.Backend.DDB, recipe, shape);
        assertThat(rowIds(ddb)).containsExactly(
                Integer.valueOf(7), Integer.valueOf(4), Integer.valueOf(3));
    }

    private static FinderMatrixHarness.OperationRecipe overlappingOr() {
        return () -> Q(1).and(DiffFinderValueFinder.intValue().greaterThan(0))
                .or(Q(1).and(DiffFinderValueFinder.textValue().endsWith("beta")));
    }

    private static Operation K(int scopeId, int rowId) {
        return DiffFinderValueFinder.scopeId().eq(scopeId)
                .and(DiffFinderValueFinder.rowId().eq(rowId));
    }

    private static Operation Q(int bucketId) {
        return DiffFinderValueFinder.bucketId().eq(bucketId);
    }

    private static List<Integer> rowIds(InvocationResult result) {
        ArrayList<Integer> ids = new ArrayList<Integer>();
        for (int i = 0; i < result.rows.size(); i++) {
            ids.add(Integer.valueOf(result.rows.get(i).rowId()));
        }
        return ids;
    }
}
