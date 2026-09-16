package io.reladynamo.testkit.diff;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The differential gate's verdict comes from this class, so its failure modes matter more than its
 * happy path. A differ that reports "identical" too easily turns the whole closed loop into
 * theatre.
 */
class TemporalRowSetDifferTest {

    @Test
    void identical_row_sets_report_no_divergence() {
        List<Map<String, Object>> a = rows(row(1, "alpha", ts(2026, 6, 1), INFINITY));
        List<Map<String, Object>> b = rows(row(1, "alpha", ts(2026, 6, 1), INFINITY));
        assertThat(TemporalRowSetDiffer.compare(a, b).isIdentical()).isTrue();
    }

    @Test
    void a_differing_value_is_reported_with_the_attribute_and_both_sides() {
        List<Map<String, Object>> a = rows(row(1, "alpha", ts(2026, 6, 1), INFINITY));
        List<Map<String, Object>> b = rows(row(1, "beta", ts(2026, 6, 1), INFINITY));
        RowSetDiff d = TemporalRowSetDiffer.compare(a, b);
        assertThat(d.isIdentical()).isFalse();
        assertThat(d.describe()).contains("label").contains("alpha").contains("beta");
    }

    @Test
    void a_one_millisecond_temporal_difference_is_a_divergence() {
        List<Map<String, Object>> a = rows(row(1, "alpha", ts(2026, 6, 1), INFINITY));
        Map<String, Object> shifted = row(1, "alpha", ts(2026, 6, 1), INFINITY);
        shifted.put("businessDateFrom", new Timestamp(((Timestamp) shifted.get("businessDateFrom")).getTime() + 1));
        RowSetDiff d = TemporalRowSetDiffer.compare(a, rows(shifted));
        assertThat(d.isIdentical()).isFalse();
        assertThat(d.describe()).contains("businessDateFrom");
    }

    @Test
    void row_order_does_not_matter_but_row_content_does() {
        List<Map<String, Object>> a = rows(
                row(1, "alpha", ts(2026, 6, 1), INFINITY),
                row(2, "beta", ts(2026, 6, 1), INFINITY));
        List<Map<String, Object>> b = rows(
                row(2, "beta", ts(2026, 6, 1), INFINITY),
                row(1, "alpha", ts(2026, 6, 1), INFINITY));
        assertThat(TemporalRowSetDiffer.compare(a, b).isIdentical()).isTrue();
    }

    @Test
    void a_missing_row_is_reported_as_missing_not_as_a_value_difference() {
        List<Map<String, Object>> a = rows(
                row(1, "alpha", ts(2026, 6, 1), INFINITY),
                row(2, "beta", ts(2026, 6, 1), INFINITY));
        List<Map<String, Object>> b = rows(row(1, "alpha", ts(2026, 6, 1), INFINITY));
        RowSetDiff d = TemporalRowSetDiffer.compare(a, b);
        assertThat(d.isIdentical()).isFalse();
        assertThat(d.describe()).containsIgnoringCase("missing");
    }

    @Test
    void an_extra_row_is_reported_as_extra() {
        List<Map<String, Object>> a = rows(row(1, "alpha", ts(2026, 6, 1), INFINITY));
        List<Map<String, Object>> b = rows(
                row(1, "alpha", ts(2026, 6, 1), INFINITY),
                row(9, "ghost", ts(2026, 6, 1), INFINITY));
        RowSetDiff d = TemporalRowSetDiffer.compare(a, b);
        assertThat(d.isIdentical()).isFalse();
        assertThat(d.describe()).containsIgnoringCase("extra");
    }

    @Test
    void two_empty_row_sets_are_identical_but_the_differ_says_so_explicitly() {
        RowSetDiff d = TemporalRowSetDiffer.compare(new ArrayList<>(), new ArrayList<>());
        assertThat(d.isIdentical()).isTrue();
        assertThat(d.describe()).containsIgnoringCase("empty");
    }

    @Test
    void int_versus_long_is_a_numeric_type_mismatch() {
        Map<String, Object> ref = row(1, "alpha", ts(2026, 6, 1), INFINITY);
        ref.put("quantity", Integer.valueOf(7));
        Map<String, Object> adp = row(1, "alpha", ts(2026, 6, 1), INFINITY);
        adp.put("quantity", Long.valueOf(7L));
        RowSetDiff d = TemporalRowSetDiffer.compare(rows(ref), rows(adp));
        assertThat(d.isIdentical()).isFalse();
        assertThat(d.describe()).contains("quantity");
    }

    // --- M-08: mapping-driven identity, deterministic order, binary equality -----------------

    @Test
    void should_use_custom_non_id_primary_key_name_as_identity() {
        EntityMapping mapping = skuMapping();
        Map<String, Object> a = skuRow("ABC", 1, "alpha");
        Map<String, Object> b = skuRow("XYZ", 1, "alpha");
        RowSetDiff d = TemporalRowSetDiffer.compare(mapping, rows(a, b), rows(a, b));
        assertThat(d.isIdentical()).isTrue();
    }

    @Test
    void should_not_treat_unrelated_foreign_key_ending_in_id_as_identity() {
        EntityMapping mapping = skuMapping();
        Map<String, Object> ref = skuRow("ABC", 1, "alpha");
        Map<String, Object> adp = skuRow("ABC", 2, "alpha");
        RowSetDiff d = TemporalRowSetDiffer.compare(mapping, rows(ref), rows(adp));
        assertThat(d.isIdentical()).isFalse();
        assertThat(d.describe()).contains("VALUE").contains("customerId");
        assertThat(d.describe()).doesNotContain("MISSING").doesNotContain("EXTRA");
    }

    @Test
    void should_produce_same_identity_when_map_insertion_order_differs() {
        EntityMapping mapping = compositeMapping();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("accountId", "acct-1");
        a.put("tenantId", "ten-A");
        a.put("label", "same");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("label", "same");
        b.put("tenantId", "ten-A");
        b.put("accountId", "acct-1");
        assertThat(TemporalRowSetDiffer.compare(mapping, rows(a), rows(b)).isIdentical()).isTrue();
    }

    @Test
    void should_treat_byte_arrays_as_equal_by_content_not_instance() {
        EntityMapping mapping = blobMapping();
        Map<String, Object> a = blobRow(1, new byte[]{1, 2, 3});
        Map<String, Object> b = blobRow(1, new byte[]{1, 2, 3});
        assertThat(a.get("payload")).isNotSameAs(b.get("payload"));
        assertThat(TemporalRowSetDiffer.compare(mapping, rows(a), rows(b)).isIdentical()).isTrue();

        Map<String, Object> c = blobRow(1, new byte[]{1, 2, 4});
        RowSetDiff d = TemporalRowSetDiffer.compare(mapping, rows(a), rows(c));
        assertThat(d.isIdentical()).isFalse();
        assertThat(d.describe()).contains("payload");
    }

    @Test
    void should_not_collapse_distinct_records_that_share_temporal_boundaries() {
        EntityMapping mapping = skuMapping();
        Map<String, Object> a = skuRow("ABC", 1, "alpha");
        Map<String, Object> b = skuRow("XYZ", 1, "beta");
        assertThat(a.get("businessDateFrom")).isEqualTo(b.get("businessDateFrom"));
        assertThat(a.get("customerId")).isEqualTo(b.get("customerId"));
        RowSetDiff d = TemporalRowSetDiffer.compare(mapping, rows(a, b), rows(a, b));
        assertThat(d.isIdentical()).isTrue();
    }

    @Test
    void should_report_duplicate_identity_rather_than_silently_drop_a_row() {
        EntityMapping mapping = skuMapping();
        Map<String, Object> a = skuRow("ABC", 1, "alpha");
        Map<String, Object> twin = skuRow("ABC", 1, "alpha");
        twin.put("label", "other-payload");
        assertThatThrownBy(() -> TemporalRowSetDiffer.compare(mapping, rows(a, twin), rows(a)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private static final Timestamp INFINITY = ts(9999, 12, 1);

    private static EntityMapping skuMapping() {
        return new EntityMapping("Product", "product",
                TemporalMapping.of(TemporalMapping.Flavour.BUSINESS_ONLY, INFINITY),
                Arrays.asList(
                        new AttributeMapping("sku", "sku", "String", true, false),
                        new AttributeMapping("customerId", "customerId", "int", false, false),
                        new AttributeMapping("label", "label", "String", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false)));
    }

    private static EntityMapping compositeMapping() {
        return new EntityMapping("TenantAccount", "tenant_account",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("tenantId", "tenantId", "String", true, false),
                        new AttributeMapping("accountId", "accountId", "String", true, false),
                        new AttributeMapping("label", "label", "String", false, false)));
    }

    private static EntityMapping blobMapping() {
        return new EntityMapping("BlobHolder", "blob_holder",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("docId", "docId", "int", true, false),
                        new AttributeMapping("payload", "payload", "byte[]", false, true)));
    }

    private static Map<String, Object> skuRow(String sku, int customerId, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", sku);
        m.put("customerId", Integer.valueOf(customerId));
        m.put("label", label);
        m.put("businessDateFrom", ts(2026, 6, 1));
        m.put("businessDateTo", INFINITY);
        return m;
    }

    private static Map<String, Object> blobRow(int id, byte[] payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", Integer.valueOf(id));
        m.put("payload", payload);
        return m;
    }

    private static Map<String, Object> row(int id, String label, Timestamp from, Timestamp thru) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balanceId", id);
        m.put("label", label);
        m.put("businessDateFrom", from);
        m.put("businessDateThru", thru);
        m.put("processingDateFrom", from);
        m.put("processingDateThru", thru);
        return m;
    }

    @SafeVarargs
    private static List<Map<String, Object>> rows(Map<String, Object>... r) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> m : r) {
            l.add(m);
        }
        return l;
    }

    private static Timestamp ts(int y, int mo, int d) {
        java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
