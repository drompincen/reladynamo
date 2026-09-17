package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §9 minimum MATCH cases that prove the harness: point mismatch, Query null pair,
 * double/decimal range, distinct OR bindings, endsWith, ordered top-one.
 */
class FinderMatrixMinimumCasesTest {

    private static FinderMatrixHarness harness;

    @BeforeAll
    static void setUp() {
        harness = FinderMatrixHarness.boot();
        List<ValueRow> seed = FixtureManifests.coreSeed();
        harness.truncateH2();
        harness.seedH2(seed);
        harness.seedDdb(seed);
        harness.awaitGsi(1, FixtureManifests.V());
        harness.awaitGsi(2, FixtureManifests.X());
        harness.awaitGsi(6, FixtureManifests.F());
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void should_reject_payload_mismatch_on_point_read() {
        FinderMatrixHarness.OperationRecipe recipe = () -> K(1, 3)
                .and(DiffFinderValueFinder.textValue().eq("CLOSED"));
        harness.matchH2(recipe, FinderShape.findOneGetItem());
        harness.matchH2(recipe, FinderShape.findManyGetItem());
        harness.matchH2(recipe, FinderShape.countGetItem());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.countGetItem());
        assertThat(count.count).isZero();
    }

    @Test
    void should_match_h2_for_is_null_and_is_not_null_on_codec_values() {
        harness.matchH2(
                () -> Q(1).and(DiffFinderValueFinder.intValue().isNull()),
                FinderShape.findManyQuery());
        InvocationResult isNull = harness.run(
                FinderMatrixHarness.Backend.DDB,
                () -> Q(1).and(DiffFinderValueFinder.intValue().isNull()),
                FinderShape.findManyQuery());
        assertThat(rowIds(isNull)).containsExactlyInAnyOrder(Integer.valueOf(5));

        harness.matchH2(
                () -> Q(1).and(DiffFinderValueFinder.intValue().isNotNull()),
                FinderShape.findManyQuery());
        InvocationResult notNull = harness.run(
                FinderMatrixHarness.Backend.DDB,
                () -> Q(1).and(DiffFinderValueFinder.intValue().isNotNull()),
                FinderShape.findManyQuery());
        assertThat(rowIds(notNull)).containsExactlyInAnyOrder(
                Integer.valueOf(1), Integer.valueOf(2), Integer.valueOf(3),
                Integer.valueOf(4), Integer.valueOf(6), Integer.valueOf(7));

        harness.matchH2(
                () -> Q(1).and(DiffFinderValueFinder.booleanValue().isNull()),
                FinderShape.findManyQuery());
    }

    @Test
    void should_match_h2_for_greater_than_and_less_than_on_double_and_decimal() {
        harness.matchH2(
                () -> Q(1).and(DiffFinderValueFinder.doubleValue().greaterThan(2.5d)),
                FinderShape.findManyQuery());
        InvocationResult gtDouble = harness.run(
                FinderMatrixHarness.Backend.DDB,
                () -> Q(1).and(DiffFinderValueFinder.doubleValue().greaterThan(2.5d)),
                FinderShape.findManyQuery());
        assertThat(rowIds(gtDouble)).containsExactlyInAnyOrder(Integer.valueOf(4), Integer.valueOf(7));

        harness.matchH2(
                () -> Q(1).and(DiffFinderValueFinder.decimalValue().greaterThan(new BigDecimal("2.5"))),
                FinderShape.findManyQuery());
        InvocationResult gtDec = harness.run(
                FinderMatrixHarness.Backend.DDB,
                () -> Q(1).and(DiffFinderValueFinder.decimalValue().greaterThan(new BigDecimal("2.5"))),
                FinderShape.findManyQuery());
        assertThat(rowIds(gtDec)).containsExactlyInAnyOrder(Integer.valueOf(4), Integer.valueOf(7));

        harness.matchH2(
                () -> Q(1).and(DiffFinderValueFinder.doubleValue().lessThan(2.5d)),
                FinderShape.findManyQuery());
        harness.matchH2(
                () -> Q(1).and(DiffFinderValueFinder.decimalValue().lessThan(new BigDecimal("2.5"))),
                FinderShape.findManyQuery());
    }

    @Test
    void should_preserve_distinct_or_value_bindings() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                K(6, 1).and(DiffFinderValueFinder.textValue().eq("A"))
                        .or(K(6, 2).and(DiffFinderValueFinder.textValue().eq("B")));
        harness.matchH2(recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyFanout());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(Integer.valueOf(1), Integer.valueOf(2));
        assertThat(ddb.rows.get(0).scopeId()).isEqualTo(6);
    }

    @Test
    void should_match_h2_for_ends_with() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                Q(1).and(DiffFinderValueFinder.textValue().endsWith("beta"));
        harness.matchH2(recipe, FinderShape.findManyQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery());
        assertThat(rowIds(ddb)).containsExactlyInAnyOrder(Integer.valueOf(3), Integer.valueOf(6));
    }

    @Test
    void should_order_across_partitions_before_top_n() {
        IntHashSet ids = new IntHashSet();
        ids.add(1);
        ids.add(2);
        ids.add(3);
        ids.add(4);
        ids.add(6);
        ids.add(7);
        FinderMatrixHarness.OperationRecipe recipe = () ->
                DiffFinderValueFinder.scopeId().eq(1).and(DiffFinderValueFinder.rowId().in(ids));
        FinderShape shape = FinderShape.findManyOrdered(
                DiffFinderValueFinder.intValue().descendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()),
                Integer.valueOf(1),
                FinderShape.Route.EXECUTE_OR_FANOUT);
        harness.matchH2(recipe, shape);
        InvocationResult ddb = harness.run(FinderMatrixHarness.Backend.DDB, recipe, shape);
        assertThat(rowIds(ddb)).containsExactly(Integer.valueOf(7));
    }

    @Test
    void should_match_h2_for_cold_find_one() {
        FinderMatrixHarness.OperationRecipe recipe = () -> K(1, 3);
        harness.matchH2(recipe, FinderShape.findOneGetItem());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findOneGetItem());
        assertThat(ddb.rows).hasSize(1);
        assertThat(ddb.rows.get(0)).isEqualTo(TypedSnapshot.ofManifest(FixtureManifests.v(3)));
        harness.matchH2(recipe, FinderShape.findOneBypassGetItem());
    }

    private static Operation K(int scopeId, int rowId) {
        return DiffFinderValueFinder.scopeId().eq(scopeId)
                .and(DiffFinderValueFinder.rowId().eq(rowId));
    }

    private static Operation Q(int bucketId) {
        return DiffFinderValueFinder.bucketId().eq(bucketId);
    }

    private static List<Integer> rowIds(InvocationResult result) {
        java.util.ArrayList<Integer> ids = new java.util.ArrayList<Integer>();
        for (int i = 0; i < result.rows.size(); i++) {
            ids.add(Integer.valueOf(result.rows.get(i).rowId()));
        }
        return ids;
    }
}
