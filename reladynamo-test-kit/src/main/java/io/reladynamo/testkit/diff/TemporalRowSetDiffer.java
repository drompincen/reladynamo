package io.reladynamo.testkit.diff;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.diff.MappedRowSetDiffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two row sets — the H2 reference and the DynamoDB adapter — and reports every difference.
 *
 * <p>This produces the differential gate's verdict, so it is deliberately strict:
 *
 * <ul>
 *   <li><b>Identity is mapping-driven</b> when an {@link EntityMapping} is supplied:
 *       {@link EntityMapping#primaryKeyAttributes()} in declaration order, plus every temporal
 *       boundary the mapping declares. A foreign key ending in {@code Id} is never identity.
 *       A primary key named {@code sku} or {@code code} always is.
 *   <li><b>Temporal values compare exactly.</b> No millisecond tolerance. Preserving temporal
 *       boundaries exactly is the adapter's entire purpose, so a differ that rounded would hide the
 *       one class of bug this suite exists to find.
 *   <li><b>Numeric values require the same representation</b> ({@code Integer} is not {@code Long}).
 *   <li><b>{@code byte[]} compares by content</b>, including arrays nested in lists/maps.
 *   <li><b>Row order is not part of the contract</b> unless the query asked for it. DynamoDB returns
 *       sort-key order; H2 returns plan order. Rows are matched by identity, not position.
 *   <li><b>Missing and extra rows are named as such</b>, never reported as value differences, because
 *       the two have completely different causes.
 *   <li><b>An empty-versus-empty comparison says so explicitly.</b> Two empty sets are equal, but a
 *       suite that silently passes on them is measuring nothing.
 * </ul>
 *
 * <p><b>Duplicate / multiset policy.</b> A row set is a set keyed by identity, not a bag. Two rows
 * in one set that share an identity throw {@link IllegalArgumentException} rather than silently
 * dropping one. See {@link MappedRowSetDiffer}.
 *
 * <p>The two-argument overload is kept for existing callers that have not yet threaded a mapping
 * through. It still uses a name heuristic ({@code *Id} plus temporal names) but sorts those names
 * so map insertion order cannot change the verdict. New code should pass an {@link EntityMapping}.
 *
 * <p>Java 11 baseline.
 */
public final class TemporalRowSetDiffer {

    private TemporalRowSetDiffer() {
    }

    public static RowSetDiff compare(List<Map<String, Object>> reference,
                                     List<Map<String, Object>> adapter) {
        return compareHeuristic(reference, adapter);
    }

    public static RowSetDiff compare(EntityMapping mapping,
                                     List<Map<String, Object>> reference,
                                     List<Map<String, Object>> adapter) {
        if (mapping == null) {
            return compareHeuristic(reference, adapter);
        }
        if (reference == null || adapter == null) {
            throw new IllegalArgumentException("both row sets are required");
        }
        List<String> out = MappedRowSetDiffer.compare(mapping, reference, adapter);
        return new RowSetDiff(out, reference.size(), adapter.size());
    }

    /**
     * Compatibility path for callers that have not supplied a mapping. Identity is every
     * attribute whose name ends in {@code Id} plus every temporal boundary, <em>sorted by
     * name</em> so two maps with the same content in different insertion order agree.
     */
    private static RowSetDiff compareHeuristic(List<Map<String, Object>> reference,
                                               List<Map<String, Object>> adapter) {
        if (reference == null || adapter == null) {
            throw new IllegalArgumentException("both row sets are required");
        }
        List<String> out = new ArrayList<>();
        Map<String, Map<String, Object>> left = byHeuristicIdentity(reference);
        Map<String, Map<String, Object>> right = byHeuristicIdentity(adapter);

        for (Map.Entry<String, Map<String, Object>> e : left.entrySet()) {
            Map<String, Object> other = right.get(e.getKey());
            if (other == null) {
                out.add("MISSING from adapter: row " + e.getKey());
                continue;
            }
            compareAllAttributes(e.getKey(), e.getValue(), other, out);
        }
        for (String key : right.keySet()) {
            if (!left.containsKey(key)) {
                out.add("EXTRA in adapter: row " + key);
            }
        }
        return new RowSetDiff(out, reference.size(), adapter.size());
    }

    private static void compareAllAttributes(String key, Map<String, Object> ref,
                                             Map<String, Object> adp, List<String> out) {
        List<String> attributes = new ArrayList<>(ref.keySet());
        for (String a : adp.keySet()) {
            if (!attributes.contains(a)) {
                attributes.add(a);
            }
        }
        Collections.sort(attributes);
        for (String a : attributes) {
            Object l = ref.get(a);
            Object r = adp.get(a);
            if (!MappedRowSetDiffer.valuesEqual(l, r)) {
                String kind = MappedRowSetDiffer.TEMPORAL.contains(a) ? "TEMPORAL" : "VALUE";
                out.add(kind + " mismatch on row " + key + ", attribute '" + a
                        + "': reference=" + MappedRowSetDiffer.render(l)
                        + " adapter=" + MappedRowSetDiffer.render(r));
            }
        }
    }

    private static Map<String, Map<String, Object>> byHeuristicIdentity(
            List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            List<String> names = new ArrayList<>();
            for (String name : row.keySet()) {
                if (name.endsWith("Id") || MappedRowSetDiffer.TEMPORAL.contains(name)) {
                    names.add(name);
                }
            }
            Collections.sort(names);
            StringBuilder key = new StringBuilder();
            for (String name : names) {
                key.append(name).append('=').append(MappedRowSetDiffer.render(row.get(name)))
                        .append('|');
            }
            String k = key.length() == 0 ? MappedRowSetDiffer.render(row) : key.toString();
            if (out.put(k, row) != null) {
                throw new IllegalArgumentException(
                        "duplicate row identity in one row set: " + k
                                + " — the identity projection cannot distinguish these rows");
            }
        }
        return out;
    }
}
