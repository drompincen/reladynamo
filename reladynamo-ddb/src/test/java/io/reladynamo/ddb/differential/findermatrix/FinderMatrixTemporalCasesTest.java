package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.ddb.differential.domain.DiffAuditFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static io.reladynamo.ddb.differential.findermatrix.FinderMatrixTemporalHarness.Entity.AUDIT;
import static io.reladynamo.ddb.differential.findermatrix.FinderMatrixTemporalHarness.Entity.BALANCE;
import static io.reladynamo.ddb.differential.findermatrix.TemporalFixtureManifests.B0;
import static io.reladynamo.ddb.differential.findermatrix.TemporalFixtureManifests.B1;
import static io.reladynamo.ddb.differential.findermatrix.TemporalFixtureManifests.B2;
import static io.reladynamo.ddb.differential.findermatrix.TemporalFixtureManifests.P0;
import static io.reladynamo.ddb.differential.findermatrix.TemporalFixtureManifests.P1;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FINDER-MATRIX.md §6.4 temporal matrix. Cold-cache paired runner; H2 is the oracle.
 * Edge-point cases remain MATCH_H2 even if materialisation currently throws.
 */
class FinderMatrixTemporalCasesTest {

    private static FinderMatrixTemporalHarness harness;

    @BeforeAll
    static void setUp() {
        harness = FinderMatrixTemporalHarness.boot();
        harness.truncateH2();
        harness.seedH2Balances(TemporalFixtureManifests.BplusBprime());
        harness.seedH2Audits(TemporalFixtureManifests.A());
        harness.seedDdbBalances(TemporalFixtureManifests.BplusBprime());
        harness.seedDdbAudits(TemporalFixtureManifests.A());
        harness.clearCold();
        harness.counters.reset();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @ParameterizedTest(name = "as-of B1+1 / {0}")
    @MethodSource("bothAsOfDates")
    void should_match_h2_at_both_asof_dates(String name, Timestamp processing, String expectedLabel) {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(C(TemporalFixtureManifests.plus1(B1), processing));
        harness.matchH2(BALANCE, recipe, FinderShape.findOneQuery());
        InvocationResult one = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findOneQuery());
        assertThat(one.rows).hasSize(1);
        assertThat(FinderMatrixTemporalHarness.labelOf(one.rows.get(0))).isEqualTo(expectedLabel);

        harness.matchH2(BALANCE, recipe, FinderShape.countQueryBase());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.countQueryBase());
        assertThat(count.count).isEqualTo(Integer.valueOf(1));
    }

    static List<Arguments> bothAsOfDates() {
        List<Arguments> out = new ArrayList<Arguments>();
        out.add(Arguments.of("P0", P0, "old"));
        out.add(Arguments.of("P1", P1, "corrected"));
        return out;
    }

    @Test
    void should_apply_default_processing_asof() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(DiffBalanceFinder.businessDate().eq(TemporalFixtureManifests.plus1(B1)));
        harness.matchH2(BALANCE, recipe, FinderShape.findOneQuery());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findOneQuery());
        assertThat(ddb.rows).hasSize(1);
        assertThat(FinderMatrixTemporalHarness.labelOf(ddb.rows.get(0))).isEqualTo("corrected");
    }

    @ParameterizedTest(name = "business boundary {0}")
    @MethodSource("businessBoundaries")
    void should_match_h2_at_business_boundary(String name, Timestamp business, String expectedLabel) {
        FinderMatrixHarness.OperationRecipe recipe = () -> BK().and(C(business, I()));
        harness.matchH2(BALANCE, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findManyQueryBase());
        if (expectedLabel == null) {
            assertThat(ddb.rows).isEmpty();
        } else {
            assertThat(ddb.rows).hasSize(1);
            assertThat(FinderMatrixTemporalHarness.labelOf(ddb.rows.get(0))).isEqualTo(expectedLabel);
        }
        assertThat(ddb.counters.dataReads()).isGreaterThan(0);
    }

    static List<Arguments> businessBoundaries() {
        List<Arguments> out = new ArrayList<Arguments>();
        out.add(Arguments.of("B0-1", TemporalFixtureManifests.minus1(B0), null));
        out.add(Arguments.of("B0", B0, "prefix"));
        out.add(Arguments.of("B1-1", TemporalFixtureManifests.minus1(B1), "prefix"));
        out.add(Arguments.of("B1", B1, "corrected"));
        out.add(Arguments.of("B2-1", TemporalFixtureManifests.minus1(B2), "corrected"));
        out.add(Arguments.of("B2", B2, "future"));
        return out;
    }

    @ParameterizedTest(name = "processing boundary {0}")
    @MethodSource("processingBoundaries")
    void should_match_h2_at_processing_boundary(String name, Timestamp processing, String expectedLabel) {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(C(TemporalFixtureManifests.plus1(B1), processing));
        harness.matchH2(BALANCE, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findManyQueryBase());
        if (expectedLabel == null) {
            assertThat(ddb.rows).isEmpty();
        } else {
            assertThat(ddb.rows).hasSize(1);
            assertThat(FinderMatrixTemporalHarness.labelOf(ddb.rows.get(0))).isEqualTo(expectedLabel);
        }
    }

    static List<Arguments> processingBoundaries() {
        List<Arguments> out = new ArrayList<Arguments>();
        out.add(Arguments.of("P0-1", TemporalFixtureManifests.minus1(P0), null));
        out.add(Arguments.of("P0", P0, "old"));
        out.add(Arguments.of("P1-1", TemporalFixtureManifests.minus1(P1), "old"));
        out.add(Arguments.of("P1", P1, "corrected"));
        return out;
    }

    @Test
    void should_match_h2_at_processing_boundary_count_at_P1() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(C(TemporalFixtureManifests.plus1(B1), P1));
        harness.matchH2(BALANCE, recipe, FinderShape.countQueryBase());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.countQueryBase());
        assertThat(count.count).isEqualTo(Integer.valueOf(1));
    }

    @Test
    void should_match_h2_for_infinity_on_both_axes() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(DiffBalanceFinder.businessDate().equalsInfinity())
                        .and(DiffBalanceFinder.processingDate().equalsInfinity());
        harness.matchH2(BALANCE, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findManyQueryBase());
        assertThat(ddb.rows).hasSize(1);
        assertThat(FinderMatrixTemporalHarness.labelOf(ddb.rows.get(0))).isEqualTo("future");
    }

    @ParameterizedTest(name = "audit processing {0}")
    @MethodSource("auditProcessing")
    void should_match_h2_for_processing_only_asof(String name, Timestamp processing, String expectedLabel) {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                DiffAuditFinder.auditId().eq(301).and(DiffAuditFinder.processingDate().eq(processing));
        harness.matchH2(AUDIT, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, AUDIT, recipe, FinderShape.findManyQueryBase());
        if (expectedLabel == null) {
            assertThat(ddb.rows).isEmpty();
        } else {
            assertThat(ddb.rows).hasSize(1);
            assertThat(FinderMatrixTemporalHarness.labelOf(ddb.rows.get(0))).isEqualTo(expectedLabel);
        }
        assertThat(ddb.counters.query.get()).isGreaterThanOrEqualTo(1);
    }

    static List<Arguments> auditProcessing() {
        List<Arguments> out = new ArrayList<Arguments>();
        out.add(Arguments.of("P0-1", TemporalFixtureManifests.minus1(P0), null));
        out.add(Arguments.of("P0", P0, "old"));
        out.add(Arguments.of("P1-1", TemporalFixtureManifests.minus1(P1), "old"));
        out.add(Arguments.of("P1", P1, "new"));
        out.add(Arguments.of("I", TemporalFixtureManifests.I(), "new"));
        return out;
    }

    @Test
    void should_match_h2_for_audit_infinity() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                DiffAuditFinder.auditId().eq(301)
                        .and(DiffAuditFinder.processingDate().equalsInfinity());
        harness.matchH2(AUDIT, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, AUDIT, recipe, FinderShape.findManyQueryBase());
        assertThat(ddb.rows).hasSize(1);
        assertThat(FinderMatrixTemporalHarness.labelOf(ddb.rows.get(0))).isEqualTo("new");
        assertThat(ddb.counters.query.get()).isGreaterThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "exact rectangle label={0}")
    @MethodSource("exactRectangleLabels")
    void should_filter_payload_on_exact_rectangle(String label, boolean expectRow) {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(DiffBalanceFinder.businessDateFrom().eq(B1))
                        .and(DiffBalanceFinder.processingDateFrom().eq(P1))
                        .and(C(TemporalFixtureManifests.plus1(B1), I()))
                        .and(DiffBalanceFinder.label().eq(label));
        harness.matchH2(BALANCE, recipe, FinderShape.findOneGetItem());
        InvocationResult one = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findOneGetItem());
        if (expectRow) {
            assertThat(one.rows).hasSize(1);
            assertThat(FinderMatrixTemporalHarness.labelOf(one.rows.get(0))).isEqualTo("corrected");
        } else {
            assertThat(one.rows).isEmpty();
        }
        harness.matchH2(BALANCE, recipe, FinderShape.countGetItem());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.countGetItem());
        assertThat(count.count).isEqualTo(Integer.valueOf(expectRow ? 1 : 0));
    }

    static List<Arguments> exactRectangleLabels() {
        List<Arguments> out = new ArrayList<Arguments>();
        out.add(Arguments.of("corrected", Boolean.TRUE));
        out.add(Arguments.of("old", Boolean.FALSE));
        return out;
    }

    @Test
    void should_filter_business_asof_on_exact_rectangle() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(DiffBalanceFinder.businessDateFrom().eq(B0))
                        .and(DiffBalanceFinder.processingDateFrom().eq(P0))
                        .and(C(B2, P0));
        harness.matchH2(BALANCE, recipe, FinderShape.findOneGetItem());
        InvocationResult one = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findOneGetItem());
        assertThat(one.rows).isEmpty();
        harness.matchH2(BALANCE, recipe, FinderShape.countGetItem());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.countGetItem());
        assertThat(count.count).isZero();
        assertThat(one.counters.getItem.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_filter_processing_asof_on_exact_rectangle() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(DiffBalanceFinder.businessDateFrom().eq(B0))
                        .and(DiffBalanceFinder.processingDateFrom().eq(P0))
                        .and(C(B1, P1));
        harness.matchH2(BALANCE, recipe, FinderShape.findOneGetItem());
        InvocationResult one = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findOneGetItem());
        assertThat(one.rows).isEmpty();
        harness.matchH2(BALANCE, recipe, FinderShape.countGetItem());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.countGetItem());
        assertThat(count.count).isZero();
        assertThat(one.counters.getItem.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_match_h2_for_all_edge_rectangles() {
        FinderMatrixHarness.OperationRecipe recipe = () -> BK().and(H());
        harness.matchH2(BALANCE, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findManyQueryBase());
        assertThat(labels(ddb)).containsExactlyInAnyOrder("old", "prefix", "corrected", "future");
        harness.matchH2(BALANCE, recipe, FinderShape.countQueryBase());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.countQueryBase());
        assertThat(count.count).isEqualTo(Integer.valueOf(4));
    }

    @Test
    void should_preserve_versions_in_overlapping_history_union() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(H()).and(DiffBalanceFinder.label().startsWith("p")
                        .or(DiffBalanceFinder.quantity().eq(10.0d)));
        harness.matchH2(BALANCE, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findManyQueryBase());
        assertThat(labels(ddb)).containsExactlyInAnyOrder("old", "prefix");
        harness.matchH2(BALANCE, recipe, FinderShape.countQueryBase());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.countQueryBase());
        assertThat(count.count).isEqualTo(Integer.valueOf(2));
    }

    @Test
    void should_match_h2_for_audit_edge_history() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                DiffAuditFinder.auditId().eq(301)
                        .and(DiffAuditFinder.processingDate().equalsEdgePoint());
        harness.matchH2(AUDIT, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, AUDIT, recipe, FinderShape.findManyQueryBase());
        assertThat(ddb.rows).hasSize(2);
        assertThat(labels(ddb)).containsExactlyInAnyOrder("old", "new");
        harness.matchH2(AUDIT, recipe, FinderShape.countQueryBase());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, AUDIT, recipe, FinderShape.countQueryBase());
        assertThat(count.count).isEqualTo(Integer.valueOf(2));
    }

    @Test
    void should_match_h2_for_business_from_range() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                BK().and(H())
                        .and(DiffBalanceFinder.processingDateFrom().eq(P1))
                        .and(DiffBalanceFinder.businessDateFrom().greaterThan(B0))
                        .and(DiffBalanceFinder.businessDateFrom().lessThan(B2));
        harness.matchH2(BALANCE, recipe, FinderShape.findManyQueryBase());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findManyQueryBase());
        assertThat(ddb.rows).hasSize(1);
        assertThat(FinderMatrixTemporalHarness.labelOf(ddb.rows.get(0))).isEqualTo("corrected");
    }

    @Test
    void should_preserve_distinct_temporal_branch_bindings() {
        FinderMatrixHarness.OperationRecipe recipe = () ->
                DiffBalanceFinder.balanceId().eq(201)
                        .and(DiffBalanceFinder.processingDateFrom().eq(P0))
                        .and(DiffBalanceFinder.businessDateFrom().greaterThan(B1))
                        .and(DiffBalanceFinder.businessDateFrom().lessThan(I()))
                        .or(DiffBalanceFinder.balanceId().eq(202)
                                .and(DiffBalanceFinder.processingDateFrom().eq(P1))
                                .and(DiffBalanceFinder.businessDateFrom().greaterThan(B0))
                                .and(DiffBalanceFinder.businessDateFrom().lessThan(B2)))
                        .and(H());
        harness.matchH2(BALANCE, recipe, FinderShape.findManyFanout());
        InvocationResult ddb = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.findManyFanout());
        assertThat(ddb.rows).hasSize(2);
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < ddb.rows.size(); i++) {
            ids.add(FinderMatrixTemporalHarness.datedIdentity(BALANCE, ddb.rows.get(i)));
        }
        assertThat(labels(ddb)).containsExactlyInAnyOrder("future", "corrected");
        harness.matchH2(BALANCE, recipe, FinderShape.countFanout());
        InvocationResult count = harness.run(
                FinderMatrixHarness.Backend.DDB, BALANCE, recipe, FinderShape.countFanout());
        assertThat(count.count).isEqualTo(Integer.valueOf(2));
    }

    private static Operation BK() {
        return DiffBalanceFinder.balanceId().eq(201);
    }

    private static Operation C(Timestamp business, Timestamp processing) {
        return DiffBalanceFinder.businessDate().eq(business)
                .and(DiffBalanceFinder.processingDate().eq(processing));
    }

    private static Operation H() {
        return DiffBalanceFinder.businessDate().equalsEdgePoint()
                .and(DiffBalanceFinder.processingDate().equalsEdgePoint());
    }

    private static Timestamp I() {
        return TemporalFixtureManifests.I();
    }

    private static List<String> labels(InvocationResult result) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < result.rows.size(); i++) {
            out.add(FinderMatrixTemporalHarness.labelOf(result.rows.get(i)));
        }
        return out;
    }
}
