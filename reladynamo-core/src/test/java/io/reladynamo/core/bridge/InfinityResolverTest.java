package io.reladynamo.core.bridge;

import io.reladynamo.core.plan.fixture.PlanCustomerFinder;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding 13. {@code MithraObjectXmlParser} cannot classload an
 * {@code infinityDate="[...getDefaultInfinity()]"} snippet, so it substitutes a conventional UTC
 * sentinel. Reladomo's real infinity is {@code 9999-12-01 23:59:00} in the <b>JVM default
 * timezone</b>. In UTC they coincide; anywhere else they do not — and finding 12 was one consequence
 * of that silent disagreement.
 *
 * <p>There must be exactly one source of truth, and it must be the generated attribute rather than a
 * value reconstructed from XML text.
 */
class InfinityResolverTest {

    @BeforeAll
    static void boot() {
        io.reladynamo.core.plan.PlanBootAccess.ensure();
    }

    @Test
    void resolves_infinity_from_the_generated_attribute_not_from_xml_text() {
        Timestamp resolved = InfinityResolver.resolve(PlanRuleFinder.getFinderInstance());
        Timestamp reladomo = PlanRuleFinder.businessDate().getInfinityDate();
        assertThat(resolved).isEqualTo(reladomo);
    }

    @Test
    void the_conventional_utc_sentinel_differs_from_reladomos_outside_utc() {
        // The bug in one assertion. If this ever stops differing, the JVM is running in UTC and the
        // mismatch is merely hidden — not absent.
        Timestamp reladomo = PlanRuleFinder.businessDate().getInfinityDate();
        Timestamp conventionalUtc = utcSentinel();
        if (!TimeZone.getDefault().getID().equals("UTC")
                && TimeZone.getDefault().getRawOffset() != 0) {
            assertThat(reladomo)
                    .as("outside UTC, Reladomo's infinity and a UTC-conventional sentinel must differ "
                            + "— that difference is what finding 12 tripped over")
                    .isNotEqualTo(conventionalUtc);
        }
    }

    @Test
    void is_stable_across_calls_so_it_can_be_compared_by_value() {
        assertThat(InfinityResolver.resolve(PlanRuleFinder.getFinderInstance()))
                .isEqualTo(InfinityResolver.resolve(PlanRuleFinder.getFinderInstance()));
    }

    @Test
    void a_non_dated_finder_has_no_infinity() {
        assertThat(InfinityResolver.resolve(PlanCustomerFinder.getFinderInstance())).isNull();
    }

    @Test
    void rejects_a_null_finder_rather_than_returning_a_default() {
        // Returning a default here is how the wrong sentinel got in originally.
        assertThatThrownBy(() -> InfinityResolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Timestamp utcSentinel() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(9999, Calendar.DECEMBER, 1, 23, 59, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
