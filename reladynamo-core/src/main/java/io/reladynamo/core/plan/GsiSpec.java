package io.reladynamo.core.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A global secondary index the planner may consider. GSI reads are eventually consistent and
 * forbidden inside a Mithra transaction, on refresh, date-range, and delete.
 */
public final class GsiSpec {

    public enum Projection { ALL, KEYS_ONLY, INCLUDE }

    /**
     * Sparse current-row GSI: partition key is the encoded logical key, sort key is
     * {@code v1#B#<businessFrom>}, attribute omitted when processingThru ≠ infinity.
     */
    public static final String SK_CURRENT_BUSINESS = "CURRENT_BUSINESS";

    /**
     * Foreign-key GSI: partition key is the FK, sort key mirrors the base table {@code sk}
     * (the temporal composite). Deep-fetch wants the current version of each child, so the
     * temporal axes stay on the index; as-of predicates remain FilterExpression containment.
     */
    public static final String SK_BASE_TEMPORAL = "BASE_TEMPORAL";

    private final String name;
    private final List<String> partitionKeyJavaNames;
    private final String sortKeyKind;
    private final boolean sparseCurrent;
    private final Projection projection;
    private final List<String> projectedJavaNames;

    public GsiSpec(String name, List<String> partitionKeyJavaNames, String sortKeyKind,
                   boolean sparseCurrent, Projection projection, List<String> projectedJavaNames) {
        this.name = Objects.requireNonNull(name, "name");
        this.partitionKeyJavaNames = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(partitionKeyJavaNames, "partitionKeyJavaNames")));
        this.sortKeyKind = sortKeyKind;
        this.sparseCurrent = sparseCurrent;
        this.projection = projection == null ? Projection.ALL : projection;
        this.projectedJavaNames = projectedJavaNames == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(projectedJavaNames));
    }

    public static GsiSpec sparseCurrent(String name, List<String> pkJavaNames) {
        return new GsiSpec(name, pkJavaNames, SK_CURRENT_BUSINESS, true, Projection.ALL, null);
    }

    public static GsiSpec uniqueAttribute(String name, String attributeJavaName) {
        return new GsiSpec(name, Collections.singletonList(attributeJavaName), null, false,
                Projection.KEYS_ONLY, null);
    }

    /**
     * Access path for a child queried by parent id: HASH is the foreign key, RANGE is the
     * base sort key so versions of the same child stay distinct and ordered.
     */
    public static GsiSpec foreignKey(String name, String fkJavaName) {
        return new GsiSpec(name, Collections.singletonList(fkJavaName), SK_BASE_TEMPORAL, false,
                Projection.ALL, null);
    }

    /**
     * Physical DynamoDB attribute holding the encoded GSI partition key
     * ({@code v1#GSI#&lt;ATTR&gt;#&lt;value&gt;}). Distinct from the table {@code pk}.
     */
    public String partitionKeyAttributeName() {
        if (partitionKeyJavaNames.size() == 1) {
            return "gsi_" + partitionKeyJavaNames.get(0);
        }
        return "gsi_pk";
    }

    /**
     * Physical DynamoDB attribute for the GSI sort key, or {@code null} when the index is HASH-only.
     * {@link #SK_BASE_TEMPORAL} reuses the table {@code sk}; sparse-current uses {@code gsi_sk}.
     */
    public String sortKeyAttributeName() {
        if (SK_CURRENT_BUSINESS.equals(sortKeyKind)) {
            return "gsi_sk";
        }
        if (SK_BASE_TEMPORAL.equals(sortKeyKind)) {
            return PhysicalDesign.SK_ATTR;
        }
        return null;
    }

    public String name() {
        return name;
    }

    public List<String> partitionKeyJavaNames() {
        return partitionKeyJavaNames;
    }

    public String sortKeyKind() {
        return sortKeyKind;
    }

    public boolean sparseCurrent() {
        return sparseCurrent;
    }

    public Projection projection() {
        return projection;
    }

    public List<String> projectedJavaNames() {
        return projectedJavaNames;
    }

    public boolean projects(String javaName) {
        if (projection == Projection.ALL) {
            return true;
        }
        if (projection == Projection.KEYS_ONLY) {
            return partitionKeyJavaNames.contains(javaName);
        }
        return projectedJavaNames.contains(javaName) || partitionKeyJavaNames.contains(javaName);
    }
}
