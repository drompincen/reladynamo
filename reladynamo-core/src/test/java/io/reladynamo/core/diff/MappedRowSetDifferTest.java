package io.reladynamo.core.diff;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inspection acceptance case 1 against {@link MappedRowSetDiffer} itself: every source PK
 * component and every mapped value participates in verification; binary values compare by
 * content; extra/missing versions are detected.
 */
class MappedRowSetDifferTest {

    @Test
    void should_treat_each_composite_pk_component_as_identity() {
        EntityMapping mapping = composite();
        Map<String, Object> a = account("ten-A", "acct-1", 10.0d, ts(2026, 1, 1));
        Map<String, Object> b = account("ten-A", "acct-2", 10.0d, ts(2026, 1, 1));

        assertThat(MappedRowSetDiffer.identityOf(mapping, a))
                .contains("tenantId=ten-A")
                .contains("accountId=acct-1");
        assertThat(MappedRowSetDiffer.identityOf(mapping, b))
                .contains("tenantId=ten-A")
                .contains("accountId=acct-2");
        assertThat(MappedRowSetDiffer.identityOf(mapping, a))
                .as("sharing tenantId must not collapse two accounts into one identity")
                .isNotEqualTo(MappedRowSetDiffer.identityOf(mapping, b));

        List<String> missing = MappedRowSetDiffer.compare(mapping, rows(a, b), rows(a));
        assertThat(joined(missing).toLowerCase()).contains("missing");
        assertThat(joined(missing)).contains("acct-2");
    }

    @Test
    void should_compare_binary_values_by_content_of_independently_constructed_arrays() {
        EntityMapping mapping = blob();
        byte[] left = new byte[] {1, 2, 3};
        byte[] right = new byte[] {1, 2, 3};
        assertThat(left).isNotSameAs(right);

        Map<String, Object> a = blobRow("SKU-1", left);
        Map<String, Object> b = blobRow("SKU-1", right);
        assertThat(MappedRowSetDiffer.compare(mapping, rows(a), rows(b))).isEmpty();
        assertThat(MappedRowSetDiffer.valuesEqual(left, right)).isTrue();

        byte[] mutated = new byte[] {1, 2, 4};
        assertThat(mutated).isNotSameAs(left);
        Map<String, Object> c = blobRow("SKU-1", mutated);
        List<String> diverged = MappedRowSetDiffer.compare(mapping, rows(a), rows(c));
        assertThat(diverged).isNotEmpty();
        assertThat(joined(diverged)).contains("payload");
        assertThat(MappedRowSetDiffer.valuesEqual(left, mutated)).isFalse();
    }

    @Test
    void should_detect_extra_and_missing_versions_on_the_same_logical_key() {
        EntityMapping mapping = dated();
        Map<String, Object> v1 = datedRow(1, 10.0d, ts(2026, 1, 1));
        Map<String, Object> v2 = datedRow(1, 11.0d, ts(2026, 6, 1));

        List<String> missing = MappedRowSetDiffer.compare(mapping, rows(v1, v2), rows(v1));
        assertThat(joined(missing).toLowerCase()).contains("missing");

        List<String> extra = MappedRowSetDiffer.compare(mapping, rows(v1), rows(v1, v2));
        assertThat(joined(extra).toLowerCase()).contains("extra");
    }

    @Test
    void should_compare_every_mapped_payload_attribute_not_only_temporal_boundaries() {
        EntityMapping mapping = dated();
        Map<String, Object> original = datedRow(1, 10.0d, ts(2026, 1, 1));
        Map<String, Object> mutated = datedRow(1, 99.0d, ts(2026, 1, 1));
        List<String> diverged = MappedRowSetDiffer.compare(mapping, rows(original), rows(mutated));
        assertThat(diverged).isNotEmpty();
        assertThat(joined(diverged)).contains("quantity");
    }

    @Test
    void should_refuse_duplicate_identities_rather_than_drop_a_row() {
        EntityMapping mapping = dated();
        Map<String, Object> a = datedRow(1, 10.0d, ts(2026, 1, 1));
        Map<String, Object> twin = datedRow(1, 99.0d, ts(2026, 1, 1));
        assertThatThrownBy(() -> MappedRowSetDiffer.compare(mapping, rows(a, twin), rows(a)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private static EntityMapping composite() {
        return new EntityMapping("com.acme.TenantAccount", "bf_composite_unit",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, ts(9999, 12, 1)),
                Arrays.asList(
                        new AttributeMapping("tenantId", "tenantId", "String", true, false),
                        new AttributeMapping("accountId", "accountId", "String", true, false),
                        new AttributeMapping("quantity", "quantity", "double", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
    }

    private static EntityMapping blob() {
        return new EntityMapping("com.acme.Product", "bf_blob_unit",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("code", "code", "String", true, false),
                        new AttributeMapping("payload", "payload", "byte[]", false, true)));
    }

    private static EntityMapping dated() {
        return new EntityMapping("com.acme.Balance", "bf_dated_unit",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, ts(9999, 12, 1)),
                Arrays.asList(
                        new AttributeMapping("balanceId", "balanceId", "int", true, false),
                        new AttributeMapping("quantity", "quantity", "double", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
    }

    private static Map<String, Object> account(String tenant, String account, double qty, Timestamp from) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("tenantId", tenant);
        m.put("accountId", account);
        m.put("quantity", Double.valueOf(qty));
        m.put("businessDateFrom", from);
        m.put("businessDateTo", ts(9999, 12, 1));
        m.put("processingDateFrom", from);
        m.put("processingDateTo", ts(9999, 12, 1));
        return m;
    }

    private static Map<String, Object> blobRow(String code, byte[] payload) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("payload", payload);
        return m;
    }

    private static Map<String, Object> datedRow(int id, double qty, Timestamp from) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("balanceId", Integer.valueOf(id));
        m.put("quantity", Double.valueOf(qty));
        m.put("businessDateFrom", from);
        m.put("businessDateTo", ts(9999, 12, 1));
        m.put("processingDateFrom", from);
        m.put("processingDateTo", ts(9999, 12, 1));
        return m;
    }

    @SafeVarargs
    private static List<Map<String, Object>> rows(Map<String, Object>... r) {
        List<Map<String, Object>> l = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < r.length; i++) {
            l.add(r[i]);
        }
        return l;
    }

    private static String joined(List<String> divergences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < divergences.size(); i++) {
            sb.append(divergences.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static Timestamp ts(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
