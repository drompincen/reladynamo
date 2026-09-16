package io.reladynamo.core.diff;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mapping-driven row-set comparison: identity comes from
 * {@link EntityMapping#primaryKeyAttributes()} in declaration order, plus every temporal
 * boundary attribute declared on the mapping. Values compare with type-aware equality
 * (exact timestamps, same numeric class, content-based {@code byte[]}).
 *
 * <p><b>Duplicate / multiset policy.</b> A row set is a <em>set</em> keyed by identity, not a
 * bag. Two rows in the same set that project to the same identity are an error — the
 * identity cannot tell them apart, so the comparison refuses to produce a verdict that
 * would silently drop one of them. Callers see {@link IllegalArgumentException} with
 * {@code duplicate row identity}. Physical extras that decode to a <em>new</em> identity
 * are reported as {@code EXTRA}; missing identities as {@code MISSING}.
 *
 * <p>Java 11 baseline.
 */
public final class MappedRowSetDiffer {

    /** Attributes that carry temporal meaning; compared exactly and included in identity. */
    public static final Set<String> TEMPORAL = Collections.unmodifiableSet(new LinkedHashSet<>(
            Arrays.asList(
                    "businessDateFrom", "businessDateTo", "processingDateFrom", "processingDateTo",
                    "businessDateThru", "processingDateThru")));

    private MappedRowSetDiffer() {
    }

    public static List<String> compare(EntityMapping mapping,
                                       List<Map<String, Object>> reference,
                                       List<Map<String, Object>> adapter) {
        if (mapping == null) {
            throw new IllegalArgumentException("entity mapping is required");
        }
        if (reference == null || adapter == null) {
            throw new IllegalArgumentException("both row sets are required");
        }
        List<String> out = new ArrayList<>();
        Map<String, Map<String, Object>> left = byIdentity(mapping, reference);
        Map<String, Map<String, Object>> right = byIdentity(mapping, adapter);

        for (Map.Entry<String, Map<String, Object>> e : left.entrySet()) {
            Map<String, Object> other = right.get(e.getKey());
            if (other == null) {
                out.add("MISSING from adapter: row " + e.getKey());
                continue;
            }
            compareRow(mapping, e.getKey(), e.getValue(), other, out);
        }
        for (String key : right.keySet()) {
            if (!left.containsKey(key)) {
                out.add("EXTRA in adapter: row " + key);
            }
        }
        return out;
    }

    /**
     * Identity string for one row: primary-key attributes in mapping declaration order,
     * then temporal boundary attributes that the mapping actually declares, also in
     * declaration order. Map insertion order is irrelevant.
     */
    public static String identityOf(EntityMapping mapping, Map<String, Object> row) {
        if (mapping == null) {
            throw new IllegalArgumentException("entity mapping is required");
        }
        if (row == null) {
            throw new IllegalArgumentException("row is required");
        }
        StringBuilder key = new StringBuilder();
        for (AttributeMapping pk : mapping.primaryKeyAttributes()) {
            key.append(pk.javaName()).append('=').append(render(row.get(pk.javaName()))).append('|');
        }
        for (AttributeMapping a : mapping.attributes()) {
            if (a.isPrimaryKey()) {
                continue;
            }
            if (TEMPORAL.contains(a.javaName())) {
                key.append(a.javaName()).append('=').append(render(row.get(a.javaName()))).append('|');
            }
        }
        return key.toString();
    }

    /** Logical partition identity: primary-key attributes only, declaration order. */
    public static String partitionIdentity(EntityMapping mapping, Map<String, Object> row) {
        if (mapping == null) {
            throw new IllegalArgumentException("entity mapping is required");
        }
        if (row == null) {
            throw new IllegalArgumentException("row is required");
        }
        StringBuilder key = new StringBuilder();
        for (AttributeMapping pk : mapping.primaryKeyAttributes()) {
            key.append(pk.javaName()).append('=').append(render(row.get(pk.javaName()))).append('|');
        }
        return key.toString();
    }

    public static boolean rowsEqual(EntityMapping mapping,
                                    Map<String, Object> left,
                                    Map<String, Object> right) {
        if (mapping == null || left == null || right == null) {
            return false;
        }
        for (AttributeMapping a : mapping.attributes()) {
            if (!valuesEqual(left.get(a.javaName()), right.get(a.javaName()))) {
                return false;
            }
        }
        return true;
    }

    public static void requireUniqueIdentities(EntityMapping mapping, List<Map<String, Object>> rows) {
        byIdentity(mapping, rows);
    }

    public static Map<String, Map<String, Object>> byIdentity(EntityMapping mapping,
                                                              List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String k = identityOf(mapping, row);
            if (out.put(k, row) != null) {
                throw new IllegalArgumentException(
                        "duplicate row identity in one row set: " + k
                                + " — the identity projection cannot distinguish these rows");
            }
        }
        return out;
    }

    public static boolean valuesEqual(Object l, Object r) {
        if (l == null || r == null) {
            return l == r;
        }
        if (l instanceof Timestamp && r instanceof Timestamp) {
            return ((Timestamp) l).getTime() == ((Timestamp) r).getTime()
                    && ((Timestamp) l).getNanos() == ((Timestamp) r).getNanos();
        }
        if (l instanceof Number && r instanceof Number && !l.getClass().equals(r.getClass())) {
            return false;
        }
        if (l instanceof byte[] && r instanceof byte[]) {
            return Arrays.equals((byte[]) l, (byte[]) r);
        }
        if (l.getClass().isArray() && r.getClass().isArray()) {
            return arraysEqual(l, r);
        }
        if (l instanceof List && r instanceof List) {
            return listsEqual((List<?>) l, (List<?>) r);
        }
        if (l instanceof Map && r instanceof Map) {
            return mapsEqual((Map<?, ?>) l, (Map<?, ?>) r);
        }
        if (l instanceof Collection && r instanceof Collection
                && !(l instanceof List) && !(r instanceof List)) {
            return collectionsEqual((Collection<?>) l, (Collection<?>) r);
        }
        return l.equals(r);
    }

    public static String render(Object o) {
        if (o == null) {
            return "<null>";
        }
        if (o instanceof Timestamp) {
            Timestamp t = (Timestamp) o;
            return t.toInstant().toString() + "(" + t.getNanos() + "ns)";
        }
        if (o instanceof byte[]) {
            return Arrays.toString((byte[]) o);
        }
        if (o.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(o);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(render(java.lang.reflect.Array.get(o, i)));
            }
            return sb.append(']').toString();
        }
        return o.toString();
    }

    private static void compareRow(EntityMapping mapping, String key,
                                   Map<String, Object> ref, Map<String, Object> adp,
                                   List<String> out) {
        for (AttributeMapping a : mapping.attributes()) {
            Object l = ref.get(a.javaName());
            Object r = adp.get(a.javaName());
            if (!valuesEqual(l, r)) {
                String kind = TEMPORAL.contains(a.javaName()) ? "TEMPORAL" : "VALUE";
                out.add(kind + " mismatch on row " + key + ", attribute '" + a.javaName()
                        + "': reference=" + render(l) + " adapter=" + render(r));
            }
        }
    }

    private static boolean arraysEqual(Object l, Object r) {
        if (!l.getClass().equals(r.getClass())) {
            return false;
        }
        if (l instanceof Object[] && r instanceof Object[]) {
            Object[] la = (Object[]) l;
            Object[] ra = (Object[]) r;
            if (la.length != ra.length) {
                return false;
            }
            for (int i = 0; i < la.length; i++) {
                if (!valuesEqual(la[i], ra[i])) {
                    return false;
                }
            }
            return true;
        }
        int n = java.lang.reflect.Array.getLength(l);
        if (n != java.lang.reflect.Array.getLength(r)) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (!valuesEqual(java.lang.reflect.Array.get(l, i),
                    java.lang.reflect.Array.get(r, i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean listsEqual(List<?> l, List<?> r) {
        if (l.size() != r.size()) {
            return false;
        }
        for (int i = 0; i < l.size(); i++) {
            if (!valuesEqual(l.get(i), r.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean mapsEqual(Map<?, ?> l, Map<?, ?> r) {
        if (l.size() != r.size()) {
            return false;
        }
        for (Map.Entry<?, ?> e : l.entrySet()) {
            boolean found = false;
            for (Map.Entry<?, ?> o : r.entrySet()) {
                if (valuesEqual(e.getKey(), o.getKey())) {
                    if (!valuesEqual(e.getValue(), o.getValue())) {
                        return false;
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static boolean collectionsEqual(Collection<?> l, Collection<?> r) {
        if (l.size() != r.size()) {
            return false;
        }
        List<Object> remaining = new ArrayList<Object>(r);
        for (Object left : l) {
            int idx = -1;
            for (int i = 0; i < remaining.size(); i++) {
                if (valuesEqual(left, remaining.get(i))) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return false;
            }
            remaining.remove(idx);
        }
        return remaining.isEmpty();
    }
}
