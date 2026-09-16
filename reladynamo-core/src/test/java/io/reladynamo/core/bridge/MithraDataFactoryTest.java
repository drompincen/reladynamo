package io.reladynamo.core.bridge;

import com.gs.fw.common.mithra.MithraDataObject;
import io.reladynamo.core.plan.fixture.PlanCustomerFinder;
import io.reladynamo.core.plan.fixture.PlanRuleData;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Creates the right {@code *Data} instance for a finder — the last piece needed before a decoded
 * DynamoDB item can become a Reladomo object.
 */
class MithraDataFactoryTest {

    @BeforeAll
    static void boot() {
        io.reladynamo.core.plan.PlanBootAccess.ensure();
    }

    @Test
    void creates_the_data_class_that_belongs_to_the_finder() {
        MithraDataObject data = MithraDataFactory.newData(PlanRuleFinder.getFinderInstance());
        assertThat(data).isInstanceOf(PlanRuleData.class);
    }

    @Test
    void creates_a_distinct_instance_each_call() {
        // Sharing one instance across rows would make every materialised object the last row read.
        MithraDataObject a = MithraDataFactory.newData(PlanRuleFinder.getFinderInstance());
        MithraDataObject b = MithraDataFactory.newData(PlanRuleFinder.getFinderInstance());
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void works_for_a_non_temporal_object_too() {
        assertThat(MithraDataFactory.newData(PlanCustomerFinder.getFinderInstance())).isNotNull();
    }

    @Test
    void round_trips_an_item_into_a_usable_object() {
        MithraDataObject data = MithraDataFactory.newData(PlanRuleFinder.getFinderInstance());
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("ruleId", Integer.valueOf(3));
        values.put("ruleName", "materialised");
        MithraDataPopulator.populate(PlanRuleFinder.getFinderInstance(), data, values);

        java.util.Map<String, Object> out =
                MithraDataAccessor.extract(PlanRuleFinder.getFinderInstance(), data);
        assertThat(out).containsEntry("ruleId", Integer.valueOf(3))
                .containsEntry("ruleName", "materialised");
    }

    @Test
    void rejects_a_null_finder() {
        assertThatThrownBy(() -> MithraDataFactory.newData(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
