package io.reladynamo.core.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A configuration option that nothing reads is worse than a missing one, because it reads as a
 * guarantee. Finding 16 was exactly that: `maxPages` was declared, documented in the security review
 * as a denial-of-service mitigation, and consumed by nothing.
 *
 * <p>Auditing the rest of `PlannerConfig` turned up three more. They are kept — the design specifies
 * them and they will be implemented — but they now <b>refuse</b> a non-default value rather than
 * accepting it and doing nothing.
 */
class InertConfigTest {

    @Test
    void unimplemented_options_refuse_a_non_default_value() {
        assertThatThrownBy(() -> PlannerConfig.builder().joinFanOutLimit(5))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not yet enforced");

        assertThatThrownBy(() -> PlannerConfig.builder().inMemoryByteCeiling(1024))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not yet enforced");
    }

    @Test
    void setting_them_to_the_default_is_allowed_so_existing_callers_are_unaffected() {
        PlannerConfig c = PlannerConfig.builder()
                .joinFanOutLimit(PlannerConfig.DEFAULT_JOIN_FAN_OUT_LIMIT)
                .inMemoryByteCeiling(PlannerConfig.DEFAULT_IN_MEMORY_BYTE_CEILING)
                .pageSize(PlannerConfig.DEFAULT_PAGE_SIZE)
                .build();
        assertThat(c.joinFanOutLimit()).isEqualTo(PlannerConfig.DEFAULT_JOIN_FAN_OUT_LIMIT);
        assertThat(c.pageSize()).isEqualTo(PlannerConfig.DEFAULT_PAGE_SIZE);
    }

    @Test
    void implemented_options_still_accept_real_values() {
        // The guard must not spread to knobs that work: these are consumed by the planner.
        PlannerConfig c = PlannerConfig.builder()
                .allowTableScan(true)
                .pkFanOutLimit(25)
                .inMemoryRowCeiling(1000)
                .maxPages(10)
                .pageSize(7)
                .build();
        assertThat(c.allowTableScan()).isTrue();
        assertThat(c.pkFanOutLimit()).isEqualTo(25);
        assertThat(c.inMemoryRowCeiling()).isEqualTo(1000);
        assertThat(c.maxPages()).isEqualTo(10);
        assertThat(c.pageSize()).isEqualTo(7);
    }
}
