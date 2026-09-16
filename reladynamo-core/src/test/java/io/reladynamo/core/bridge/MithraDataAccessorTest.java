package io.reladynamo.core.bridge;

import io.reladynamo.core.plan.fixture.PlanPosition;
import io.reladynamo.core.plan.fixture.PlanPositionFinder;
import io.reladynamo.core.plan.fixture.PlanRule;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bridge from a Reladomo data object to the flat attribute map the codec consumes.
 *
 * <p>Reladomo has no generic {@code Attribute.valueOf(Object)} and no {@code isAttributeNull} on the
 * base class — extraction is typed per subclass. So this is explicit dispatch, and the risk is
 * silent omission: an attribute type nobody dispatched simply disappears from the map, and the row
 * persists with a field missing rather than failing. These tests exist to make that impossible.
 */
class MithraDataAccessorTest {

    @BeforeAll
    static void boot() {
        BridgeTestSupport.boot();
    }

    @Test
    void extracts_every_persistent_attribute_of_a_bitemporal_object() {
        PlanPosition p = new PlanPosition(businessDate(2026, 6, 1));
        p.setAccountId(7L);
        p.setProductId(3);
        p.setQuantity(12.5);
        p.setStatus("OPEN");

        Map<String, Object> values =
                MithraDataAccessor.extract(PlanPositionFinder.getFinderInstance(), p.zGetCurrentData());

        assertThat(values).containsEntry("accountId", 7L)
                .containsEntry("productId", 3)
                .containsEntry("quantity", 12.5)
                .containsEntry("status", "OPEN");
    }

    @Test
    void covers_every_declared_attribute_so_none_can_be_silently_dropped() {
        PlanRule r = new PlanRule(businessDate(2026, 6, 1));
        r.setRuleId(1);
        r.setRuleName("r1");
        r.setPriority(10);
        r.setResultLabel("COOL");
        r.setActive(true);

        Map<String, Object> values =
                MithraDataAccessor.extract(PlanRuleFinder.getFinderInstance(), r.zGetCurrentData());

        // getPersistentAttributes() includes the temporal boundary columns, so the map carries the
        // business attributes AND all four temporal boundaries — which is exactly what persistence
        // needs. A type the dispatch forgot would show up here as a missing key.
        assertThat(values.keySet())
                .containsExactlyInAnyOrder("ruleId", "ruleName", "priority", "resultLabel", "active",
                        "businessDateFrom", "businessDateTo",
                        "processingDateFrom", "processingDateTo");
        assertThat(values).containsEntry("active", Boolean.TRUE)
                .containsEntry("priority", 10);
    }

    @Test
    void extracts_the_temporal_boundaries_of_a_bitemporal_object() {
        PlanPosition p = new PlanPosition(businessDate(2026, 6, 1));
        p.setAccountId(1L);
        p.setProductId(1);
        p.setQuantity(1.0);
        p.setStatus("OPEN");

        Map<String, Object> t =
                MithraDataAccessor.extractTemporal(PlanPositionFinder.getFinderInstance(), p.zGetCurrentData());

        // Both axes, four boundaries. These are what the differential gate compares exactly.
        assertThat(t).containsKeys("businessDateFrom", "businessDateTo",
                "processingDateFrom", "processingDateTo");
        // Values may be null on an object that has not been written yet — Reladomo populates the
        // boundaries at persist time. The contract under test is that all four keys are produced for
        // a bitemporal entity, which is what the differential gate compares.
        assertThat(t).hasSize(4);
    }

    @Test
    void a_non_temporal_object_yields_no_temporal_boundaries() {
        Map<String, Object> t = MithraDataAccessor.extractTemporal(
                io.reladynamo.core.plan.fixture.PlanCustomerFinder.getFinderInstance(), null);
        assertThat(t).isEmpty();
    }

    @Test
    void rejects_a_null_finder_rather_than_returning_an_empty_map() {
        // An empty map would look like "an object with no attributes" and persist an empty item.
        assertThatThrownBy(() -> MithraDataAccessor.extract(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Timestamp businessDate(int y, int m, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
