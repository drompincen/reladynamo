package io.reladynamo.core.plan;

import org.junit.jupiter.api.Test;

import static io.reladynamo.core.plan.PlanFixtures.plan;
import static io.reladynamo.core.plan.PlanFixtures.planCustomer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 20: {@code avgItemBytes} was declared on {@code PlannerConfig} <em>and</em> on
 * {@code PhysicalDesign}, stored by both, exposed by both, and read by <b>nothing</b> anywhere in the
 * tree. Each class's getter vouched for the other's, so the spec-drift gate saw a consumer that did
 * not exist.
 *
 * <p>A settable knob that does nothing is worse than a missing one, because it reads as a guarantee.
 * The plan already reports {@code estimatedItemsExamined}; turning that into bytes and read units is
 * the one thing {@code avgItemBytes} can mean, and it makes the explain output comparable across
 * access paths, which is what plan cost estimates are for.
 *
 * <p>RCU arithmetic per the DynamoDB read-capacity rules: reads are billed in <b>4 KB</b> units, a
 * strongly consistent read costs a whole unit and an eventually consistent read costs half, and
 * Query/Scan are charged on the bytes <em>examined</em> rather than returned.
 */
class QueryPlanRcuEstimateTest {

    static {
        PlanReladomoBoot.ensure();
    }

    @Test
    void should_estimate_bytes_examined_from_the_configured_average_item_size() {
        PlannerConfig config = PlannerConfig.builder().avgItemBytes(2048).build();
        QueryPlan plan = plan(PlanFixtures.customerById(9), planCustomer(), config);

        assertThat(plan.avgItemBytes())
                .as("QueryPlanner must copy PlannerConfig.avgItemBytes onto the plan; a knob that "
                        + "reaches no plan is the finding-20 bug")
                .isEqualTo(2048);
        assertThat(plan.estimatedBytesExamined())
                .as("bytes examined = items examined x average item size")
                .isEqualTo((long) plan.estimatedItemsExamined() * 2048L);
    }

    @Test
    void should_charge_a_whole_read_unit_per_4kb_when_the_read_is_strongly_consistent() {
        // 3 items x 4096 bytes = 12288 bytes = exactly 3 units, consistent -> 3 RCU.
        QueryPlan plan = QueryPlan.builder()
                .className("X").tableName("T").kind(PlanKind.QUERY)
                .estimatedItemsExamined(3)
                .avgItemBytes(4096)
                .consistentRead(true)
                .build();

        assertThat(plan.estimatedRcu()).isEqualTo(3.0d);
    }

    @Test
    void should_charge_half_a_read_unit_per_4kb_when_the_read_is_eventually_consistent() {
        QueryPlan plan = QueryPlan.builder()
                .className("X").tableName("T").kind(PlanKind.QUERY)
                .estimatedItemsExamined(3)
                .avgItemBytes(4096)
                .consistentRead(false)
                .build();

        assertThat(plan.estimatedRcu())
                .as("an eventually consistent read is half the cost of a strongly consistent one")
                .isEqualTo(1.5d);
    }

    @Test
    void should_round_a_partial_4kb_block_up_to_a_whole_unit() {
        // 1 item of 100 bytes still reads one 4 KB block.
        QueryPlan plan = QueryPlan.builder()
                .className("X").tableName("T").kind(PlanKind.QUERY)
                .estimatedItemsExamined(1)
                .avgItemBytes(100)
                .consistentRead(true)
                .build();

        assertThat(plan.estimatedRcu())
                .as("DynamoDB bills whole 4 KB blocks; a 100-byte read is not free")
                .isEqualTo(1.0d);
    }

    @Test
    void should_report_zero_when_nothing_is_examined() {
        QueryPlan plan = QueryPlan.builder()
                .className("X").tableName("T").kind(PlanKind.GET_ITEM)
                .estimatedItemsExamined(0)
                .avgItemBytes(1024)
                .build();

        assertThat(plan.estimatedRcu()).isZero();
        assertThat(plan.estimatedBytesExamined()).isZero();
    }
}
