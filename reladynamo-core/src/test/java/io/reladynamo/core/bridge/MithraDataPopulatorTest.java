package io.reladynamo.core.bridge;

import io.reladynamo.core.plan.fixture.PlanRuleData;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The inverse of {@link MithraDataAccessor}: a decoded DynamoDB item back into a Reladomo data
 * object. Needed before {@code find()} can return real objects rather than raw rows.
 *
 * <p>Round-tripping through both directions is the property that matters — an extractor and a
 * populator that disagree about a name or a type would corrupt data in a way neither side's own
 * tests would notice.
 */
class MithraDataPopulatorTest {

    @BeforeAll
    static void boot() {
        io.reladynamo.core.plan.PlanBootAccess.ensure();
    }

    @Test
    void round_trips_every_attribute_through_extract_then_populate() {
        // Materialisation builds the *Data object directly, exactly as a deserializer does — not a
        // transactional object graph. Constructing one here keeps the test on the real path.
        PlanRuleData source = new PlanRuleData();
        source.setRuleId(7);
        source.setRuleName("r-seven");
        source.setPriority(42);
        source.setResultLabel("RETRO");
        source.setActive(true);
        // A stored row always carries its boundaries; supply them so the round trip is realistic.
        source.setBusinessDateFrom(businessDate(2026, 6, 1));
        source.setBusinessDateTo(INFINITY);
        source.setProcessingDateFrom(businessDate(2026, 6, 1));
        source.setProcessingDateTo(INFINITY);

        Map<String, Object> extracted =
                MithraDataAccessor.extract(PlanRuleFinder.getFinderInstance(), source);

        PlanRuleData target = new PlanRuleData();
        MithraDataPopulator.populate(PlanRuleFinder.getFinderInstance(), target, extracted);

        Map<String, Object> reExtracted =
                MithraDataAccessor.extract(PlanRuleFinder.getFinderInstance(), target);

        assertThat(reExtracted).containsEntry("ruleId", 7)
                .containsEntry("ruleName", "r-seven")
                .containsEntry("priority", 42)
                .containsEntry("resultLabel", "RETRO")
                .containsEntry("active", Boolean.TRUE);
    }

    @Test
    void a_null_boundary_is_refused_because_a_dated_row_always_has_one() {
        PlanRuleData target = new PlanRuleData();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("businessDateTo", null);
        assertThatThrownBy(() -> MithraDataPopulator.populate(
                PlanRuleFinder.getFinderInstance(), target, values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("businessDateTo");
    }

    @Test
    void a_null_in_the_map_is_written_as_a_null_attribute_not_skipped() {
        // Skipping would leave whatever the fresh data object happened to hold — a default that
        // reads as real data. Nullable columns must come back null, not zero or empty.
        PlanRuleData target = new PlanRuleData();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ruleName", null);
        MithraDataPopulator.populate(PlanRuleFinder.getFinderInstance(), target, values);

        Map<String, Object> out =
                MithraDataAccessor.extract(PlanRuleFinder.getFinderInstance(), target);
        assertThat(out.get("ruleName")).isNull();
    }

    @Test
    void an_unknown_attribute_name_fails_rather_than_being_ignored() {
        // A silently ignored column means the item had data the object never received. That is
        // exactly the kind of loss that shows up much later as a missing field.
        PlanRuleData target = new PlanRuleData();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("noSuchAttribute", "x");

        assertThatThrownBy(() -> MithraDataPopulator.populate(
                PlanRuleFinder.getFinderInstance(), target, values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noSuchAttribute");
    }

    @Test
    void rejects_a_null_finder() {
        assertThatThrownBy(() -> MithraDataPopulator.populate(null, null, new LinkedHashMap<>()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final Timestamp INFINITY = ts(9999, 12, 1);

    private static Timestamp ts(int y, int m, int d) {
        return businessDate(y, m, d);
    }

    private static Timestamp businessDate(int y, int m, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
