package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cold-cache protocol proof. A second findOne on the same complete-key Operation is
 * served from Reladomo's object cache unless both caches are cleared.
 */
class FinderMatrixColdCacheTest {

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
    void should_fail_when_cache_is_allowed_to_serve_the_query() {
        Operation op = DiffFinderValueFinder.scopeId().eq(1)
                .and(DiffFinderValueFinder.rowId().eq(3));
        harness.clearCold();
        harness.sql.allow();
        harness.counters.reset();
        harness.recording.reset();
        harness.portal.setMithraObjectReader(harness.recording.proxy());
        try {
            assertThat(DiffFinderValueFinder.findOne(op)).isNotNull();
            assertThat(harness.counters.dataReads())
                    .as("first findOne must reach DynamoDB: %s", harness.counters.describe())
                    .isGreaterThan(0);

            harness.counters.reset();
            harness.recording.reset();
            assertThat(DiffFinderValueFinder.findOne(op)).isNotNull();
            assertThat(harness.counters.dataReads())
                    .as("second findOne without cache clear must be a Reladomo cache hit: %s",
                            harness.counters.describe())
                    .isZero();
            assertThatThrownBy(() -> RequestAssertions.requirePositiveDataReads(
                    harness.counters.dataReads(), "warm cache"))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("data-read request delta was 0");
        } finally {
            harness.portal.setMithraObjectReader(harness.jdbcReader);
            harness.clearCold();
        }
    }

    @Test
    void should_reach_adapter_after_cold_reset() {
        FinderMatrixHarness.OperationRecipe recipe =
                () -> DiffFinderValueFinder.bucketId().eq(1);
        InvocationResult first = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery(), true);
        assertThat(first.rows).hasSize(7);
        assertThat(first.counters.dataReads()).isGreaterThan(0);
        InvocationResult second = harness.run(
                FinderMatrixHarness.Backend.DDB, recipe, FinderShape.findManyQuery(), true);
        assertThat(second.rows).hasSize(7);
        assertThat(second.counters.dataReads())
                .as("cold protocol must produce a new DDB read: %s", second.counters.describe())
                .isGreaterThan(0);
        assertThat(second.readerEntries).isGreaterThan(0);
        assertThat(second.counters.scan.get()).isZero();
    }
}
