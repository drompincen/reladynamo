package io.reladynamo.core.plan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C-09: ordering must compare BigDecimal exactly, byte[] by unsigned content, and refuse
 * types whose order cannot be established rather than falling back to String.valueOf.
 */
final class RowOrderComparatorTest {

    /** 2^53 — the last integer a double represents exactly. */
    private static final BigDecimal TWO_POW_53 = new BigDecimal("9007199254740992");
    /** 2^53 + 1 — distinct as BigDecimal, identical once converted to double. */
    private static final BigDecimal TWO_POW_53_PLUS_ONE = new BigDecimal("9007199254740993");

    @Test
    void should_order_adjacent_bigdecimals_when_they_straddle_double_precision() {
        RowOrderComparator comparator = ascending("amount");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("p1", TWO_POW_53_PLUS_ONE, "late"));
        rows.add(row("p2", TWO_POW_53, "early"));
        rows.add(row("p3", TWO_POW_53_PLUS_ONE, "also-late"));

        Collections.sort(rows, comparator);

        assertThat(amounts(rows)).containsExactly(
                TWO_POW_53, TWO_POW_53_PLUS_ONE, TWO_POW_53_PLUS_ONE);
        assertThat((String) rows.get(0).get("id")).isEqualTo("p2");
    }

    @Test
    void should_select_true_maximum_when_descending_top_one_lives_in_last_partition() {
        RowOrderComparator comparator = descending("amount");
        List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
        merged.addAll(partition("p1", TWO_POW_53));
        merged.addAll(partition("p2", TWO_POW_53));
        merged.addAll(partition("p3", TWO_POW_53_PLUS_ONE));

        Collections.sort(merged, comparator);
        Map<String, Object> topOne = merged.get(0);

        assertThat((BigDecimal) topOne.get("amount"))
                .as("descending top-one must be 2^53+1 from the last partition, not a false tie")
                .isEqualByComparingTo(TWO_POW_53_PLUS_ONE);
        assertThat(topOne.get("id")).isEqualTo("p3");
    }

    @Test
    void should_not_let_secondary_key_override_distinct_bigdecimal_primary() {
        RowOrderComparator comparator = new RowOrderComparator(Arrays.asList(
                new SortTerm("amount", true),
                new SortTerm("id", true)));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("aaa", TWO_POW_53_PLUS_ONE, "late"));
        rows.add(row("zzz", TWO_POW_53, "early"));

        Collections.sort(rows, comparator);

        assertThat(ids(rows))
                .as("a false double-tie would order by id (aaa before zzz) and hide the true amount order")
                .containsExactly("zzz", "aaa");
    }

    @Test
    void should_break_equal_numeric_ties_with_secondary_key() {
        RowOrderComparator comparator = new RowOrderComparator(Arrays.asList(
                new SortTerm("amount", true),
                new SortTerm("id", true)));
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("zeta", new BigDecimal("1.10"), "z"));
        rows.add(row("alpha", new BigDecimal("1.1"), "a"));

        Collections.sort(rows, comparator);

        assertThat(ids(rows)).containsExactly("alpha", "zeta");
    }

    @Test
    void should_sort_nulls_first_on_the_ordered_attribute() {
        RowOrderComparator comparator = ascending("amount");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(row("late", TWO_POW_53, "x"));
        rows.add(row("missing", null, "x"));
        rows.add(row("early", TWO_POW_53_PLUS_ONE, "x"));

        Collections.sort(rows, comparator);

        assertThat(ids(rows)).containsExactly("missing", "late", "early");
    }

    @Test
    void should_order_byte_arrays_by_unsigned_content_not_identity() {
        RowOrderComparator comparator = ascending("payload");
        byte[] low = new byte[] {0x01, 0x00};
        byte[] high = new byte[] {(byte) 0xff};
        byte[] lowCopy = new byte[] {0x01, 0x00};

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(blob("high", high));
        rows.add(blob("low-a", low));
        rows.add(blob("low-b", lowCopy));

        assertThat(comparator.compare(blob("low-a", low), blob("low-b", lowCopy)))
                .as("independently constructed equal content must compare equal")
                .isEqualTo(0);
        assertThat(comparator.compare(blob("low", low), blob("high", high)))
                .as("unsigned 0x01,0x00 must sort before 0xFF")
                .isLessThan(0);
        assertThat(comparator.compare(blob("high", high), blob("low", low))).isGreaterThan(0);

        Collections.sort(rows, comparator);
        assertThat(ids(rows)).containsExactly("low-a", "low-b", "high");
    }

    @Test
    void should_order_empty_byte_array_before_non_empty_and_prefix_before_extension() {
        RowOrderComparator comparator = ascending("payload");
        Map<String, Object> empty = blob("empty", new byte[0]);
        Map<String, Object> prefix = blob("prefix", new byte[] {0x00});
        Map<String, Object> extension = blob("extension", new byte[] {0x00, (byte) 0xFF});
        Map<String, Object> highBit = blob("high-bit", new byte[] {(byte) 0x80});

        assertThat(comparator.compare(empty, prefix)).isLessThan(0);
        assertThat(comparator.compare(prefix, extension)).isLessThan(0);
        assertThat(comparator.compare(extension, highBit)).isLessThan(0);
        assertThat(comparator.compare(empty, empty)).isEqualTo(0);

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(highBit);
        rows.add(extension);
        rows.add(empty);
        rows.add(prefix);
        Collections.sort(rows, comparator);
        assertThat(ids(rows)).containsExactly("empty", "prefix", "extension", "high-bit");
    }

    @Test
    void should_order_non_ascii_strings_by_character_content() {
        RowOrderComparator comparator = ascending("name");
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(named("zeta", "Ångström"));
        rows.add(named("cafe", "café"));
        rows.add(named("alpha", "alpha"));

        Collections.sort(rows, comparator);

        List<String> names = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            names.add((String) rows.get(i).get("name"));
        }
        List<String> expected = new ArrayList<String>(names);
        Collections.sort(expected);
        assertThat(names)
                .as("non-ASCII strings must sort by Java String content, not identity text")
                .containsExactlyElementsOf(expected);
        assertThat(names).containsExactly("alpha", "café", "Ångström");
    }

    @Test
    void should_treat_scale_variants_as_equal_when_bigdecimals_compare_to_zero() {
        RowOrderComparator comparator = ascending("amount");
        Map<String, Object> scaled = row("scaled", new BigDecimal("1.10"), "x");
        Map<String, Object> shortForm = row("short", new BigDecimal("1.1"), "x");

        assertThat(comparator.compare(scaled, shortForm))
                .as("R-04: BigDecimal.compareTo treats 1.10 and 1.1 as equal")
                .isEqualTo(0);
        assertThat(new BigDecimal("1.10").compareTo(new BigDecimal("1.1"))).isEqualTo(0);
    }

    @Test
    void should_refuse_incomparable_types_by_plan_code_instead_of_string_value_of() {
        RowOrderComparator comparator = ascending("amount");
        Map<String, Object> number = valueRow("n", "amount", Integer.valueOf(1));
        Map<String, Object> text = valueRow("t", "amount", "1");

        assertThatThrownBy(() -> comparator.compare(number, text))
                .isInstanceOf(ReladynamoUnplannableOperationException.class)
                .hasMessageContaining("RELADYNAMO-PLAN-013")
                .hasMessageContaining("Integer")
                .hasMessageContaining("String");
    }

    private static RowOrderComparator ascending(String attribute) {
        return new RowOrderComparator(Collections.singletonList(new SortTerm(attribute, true)));
    }

    private static RowOrderComparator descending(String attribute) {
        return new RowOrderComparator(Collections.singletonList(new SortTerm(attribute, false)));
    }

    private static List<Map<String, Object>> partition(String id, BigDecimal amount) {
        return Collections.singletonList(row(id, amount, id));
    }

    private static Map<String, Object> row(String id, BigDecimal amount, String name) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("amount", amount);
        row.put("name", name);
        return row;
    }

    private static Map<String, Object> blob(String id, byte[] payload) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("payload", payload);
        return row;
    }

    private static Map<String, Object> named(String id, String name) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("name", name);
        return row;
    }

    private static Map<String, Object> valueRow(String id, String attribute, Object value) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put(attribute, value);
        return row;
    }

    private static List<BigDecimal> amounts(List<Map<String, Object>> rows) {
        List<BigDecimal> out = new ArrayList<BigDecimal>();
        for (int i = 0; i < rows.size(); i++) {
            out.add((BigDecimal) rows.get(i).get("amount"));
        }
        return out;
    }

    private static List<String> ids(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            out.add((String) rows.get(i).get("id"));
        }
        return out;
    }
}
