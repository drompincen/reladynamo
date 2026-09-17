package io.reladynamo.core.plan;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.temporal.TemporalEncoder;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * v1 key grammar. Timestamps go through {@link TemporalEncoder}; never ISO-8601 in the key.
 *
 * <pre>
 *   PK = v1#&lt;SimpleClass&gt;#&lt;pk1&gt;#&lt;pk2&gt;…
 *   SK bitemporal = v1#P#&lt;processingFrom&gt;#B#&lt;businessFrom&gt;
 *   SK non-dated  = v1#ND
 * </pre>
 */
public final class PartitionKeyEncoder {

    private PartitionKeyEncoder() {
    }

    public static String partitionKey(PhysicalDesign design, Map<String, Object> pkValues) {
        StringBuilder sb = new StringBuilder();
        sb.append("v1#").append(design.simpleClassName().toUpperCase(java.util.Locale.ROOT));
        for (AttributeMapping pk : design.primaryKeyAttributes()) {
            Object value = pkValues.get(pk.javaName());
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing primary-key value for " + design.className() + "." + pk.javaName());
            }
            sb.append('#').append(encodeComponent(value));
        }
        return sb.toString();
    }

    public static String sortKey(PhysicalDesign design, Timestamp processingFrom, Timestamp businessFrom) {
        TemporalMapping.Flavour flavour = design.temporal().flavour();
        if (flavour == TemporalMapping.Flavour.NONE) {
            return PhysicalDesign.NON_DATED_SK;
        }
        StringBuilder sb = new StringBuilder("v1");
        if (flavour == TemporalMapping.Flavour.BITEMPORAL || flavour == TemporalMapping.Flavour.AUDIT_ONLY) {
            if (processingFrom == null) {
                return null;
            }
            sb.append("#P#").append(TemporalEncoder.encode(processingFrom));
        }
        if (flavour == TemporalMapping.Flavour.BITEMPORAL || flavour == TemporalMapping.Flavour.BUSINESS_ONLY) {
            if (businessFrom == null) {
                return flavour == TemporalMapping.Flavour.BUSINESS_ONLY ? null : sb.toString();
            }
            sb.append("#B#").append(TemporalEncoder.encode(businessFrom));
        }
        return sb.toString();
    }

    public static String processingPrefix(Timestamp processingFrom) {
        return "v1#P#" + TemporalEncoder.encode(processingFrom) + "#B#";
    }

    public static String currentBusinessSk(Timestamp businessFrom) {
        return "v1#B#" + TemporalEncoder.encode(businessFrom);
    }

    public static String encodeComponent(Object value) {
        return io.reladynamo.core.key.KeyComponentEncoder.encode(value);
    }

    /**
     * Encoded GSI partition key for a single non-PK attribute (FK or unique lookup).
     * Shared by the planner and the writer so a Query can find what a Put stamped.
     */
    public static String gsiPartitionKey(String attributeJavaName, Object value) {
        if (attributeJavaName == null) {
            throw new IllegalArgumentException("GSI partition-key attribute name is required");
        }
        return "v1#GSI#" + attributeJavaName.toUpperCase(java.util.Locale.ROOT)
                + "#" + encodeComponent(value);
    }

    /**
     * Encoded GSI partition key for one or more attributes. A single non-PK attribute keeps
     * {@code v1#GSI#&lt;ATTR&gt;#&lt;value&gt;}; composite keys join each component with the
     * same {@link io.reladynamo.core.key.KeyComponentEncoder} used by the base partition key.
     */
    public static String gsiPartitionKey(List<String> attributeJavaNames, Map<String, Object> values) {
        if (attributeJavaNames == null || attributeJavaNames.isEmpty()) {
            throw new IllegalArgumentException("GSI partition-key attribute names are required");
        }
        if (attributeJavaNames.size() == 1) {
            String attr = attributeJavaNames.get(0);
            Object value = values == null ? null : values.get(attr);
            return gsiPartitionKey(attr, value);
        }
        if (values == null) {
            throw new IllegalArgumentException("GSI partition-key values are required");
        }
        StringBuilder sb = new StringBuilder("v1#GSI");
        for (int i = 0; i < attributeJavaNames.size(); i++) {
            String attr = attributeJavaNames.get(i);
            Object value = values.get(attr);
            if (value == null) {
                throw new IllegalArgumentException("missing GSI partition-key value for " + attr);
            }
            sb.append('#').append(attr.toUpperCase(java.util.Locale.ROOT))
                    .append('#').append(encodeComponent(value));
        }
        return sb.toString();
    }

    public static String encodeTimestamp(Timestamp ts) {
        return TemporalEncoder.encode(ts);
    }
}
