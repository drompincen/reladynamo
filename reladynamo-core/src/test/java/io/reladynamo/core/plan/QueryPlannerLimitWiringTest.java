package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.All;
import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.plan.fixture.PlanCustomerFinder;
import io.reladynamo.core.plan.fixture.PlanPositionFinder;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;

import static io.reladynamo.core.plan.PlanFixtures.plan;
import static io.reladynamo.core.plan.PlanFixtures.planCustomer;
import static io.reladynamo.core.plan.PlanFixtures.planPosition;
import static io.reladynamo.core.plan.PlanFixtures.planRule;
import static io.reladynamo.core.plan.PlanFixtures.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * R-09: {@code PlannerConfig.maxPages} must reach the plan a real finder produces.
 *
 * <p>Hand-built {@code QueryPlan} tests can set {@code maxPages} and pass while every plan
 * {@link QueryPlanner} emits still has {@code maxPages == 0} (unbounded).
 */
class QueryPlannerLimitWiringTest {

    static {
        PlanReladomoBoot.ensure();
    }

    @Test
    void should_copy_configured_maxPages_onto_a_real_finder_query_plan() {
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        PlannerConfig config = PlannerConfig.builder().maxPages(7).build();

        QueryPlan plan = plan(PlanFixtures.ruleCurrent(42, asOf), planRule(), config);

        assertThat(plan.kind()).isEqualTo(PlanKind.QUERY);
        assertThat(plan.maxPages())
                .as("QueryPlanner must copy PlannerConfig.maxPages onto the plan a finder produces")
                .isEqualTo(7);
    }

    @Test
    void should_copy_default_maxPages_so_real_finder_plans_are_not_unbounded() {
        QueryPlan plan = plan(PlanFixtures.customerById(9), planCustomer());

        assertThat(plan.maxPages())
                .as("default PlannerConfig.maxPages is 64; 0 means unbounded and is the R-09 bug")
                .isEqualTo(PlannerConfig.DEFAULT_MAX_PAGES);
        assertThat(plan.maxPages()).isPositive();
    }

    @Test
    void should_copy_maxPages_onto_scan_and_fan_out_plans() {
        PlannerConfig scanConfig = PlannerConfig.builder()
                .allowTableScan(true)
                .maxPages(3)
                .build();
        QueryPlan scan = plan(new All(PlanCustomerFinder.id()), planCustomer(), scanConfig);
        assertThat(scan.kind()).isEqualTo(PlanKind.SCAN);
        assertThat(scan.maxPages()).isEqualTo(3);

        IntHashSet ids = new IntHashSet();
        ids.add(1);
        ids.add(2);
        ids.add(3);
        PlannerConfig fanConfig = PlannerConfig.builder().maxPages(5).build();
        QueryPlan fan = plan(PlanCustomerFinder.id().in(ids), planCustomer(), fanConfig);
        assertThat(fan.kind()).isEqualTo(PlanKind.QUERY_FAN_OUT);
        assertThat(fan.maxPages()).isEqualTo(5);
        assertThat(fan.fanOut().get(0).maxPages()).isEqualTo(5);
    }

    @Test
    void should_copy_inMemoryRowCeiling_and_pageSize_from_public_config() {
        PlannerConfig config = PlannerConfig.builder()
                .inMemoryRowCeiling(11)
                .pageSize(1)
                .maxPages(4)
                .build();
        QueryPlan plan = plan(PlanFixtures.customerById(1), planCustomer(), config);

        assertThat(plan.inMemoryRowCeiling()).isEqualTo(11);
        assertThat(plan.pageSize()).isEqualTo(1);
        assertThat(plan.maxPages()).isEqualTo(4);
    }

    @Test
    void should_reject_cartesian_pk_expansion_before_allocating_the_product() {
        LongHashSet accounts = new LongHashSet();
        IntHashSet products = new IntHashSet();
        for (int i = 1; i <= 1500; i++) {
            accounts.add((long) i);
            products.add(i);
        }
        Timestamp asOf = utc(2026, java.util.Calendar.JUNE, 1);
        Operation op = PlanPositionFinder.accountId().in(accounts)
                .and(PlanPositionFinder.productId().in(products))
                .and(PlanPositionFinder.businessDate().eq(asOf));
        PlannerConfig config = PlannerConfig.builder().pkFanOutLimit(50).build();

        // 1500 × 1500 combinations. Checking the product first is instant; building the list
        // first is the allocation the limit exists to prevent.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThatThrownBy(() -> plan(op, planPosition(), config))
                    .isInstanceOf(ReladynamoUnplannableOperationException.class)
                    .hasMessageContaining("RELADYNAMO-PLAN-002")
                    .hasMessageContaining("pkFanOutLimit=50");
        });
    }
}
