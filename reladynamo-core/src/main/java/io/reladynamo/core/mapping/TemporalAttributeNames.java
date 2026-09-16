package io.reladynamo.core.mapping;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the Java / item names of each temporal axis from an {@link EntityMapping}.
 *
 * <p>The mapping contract ({@link io.reladynamo.core.config.TemporalMapping}) stores flavour
 * and infinity, not axis names. Reladomo allows those names to be anything; the writer and
 * planner historically assumed {@code businessDateFrom}/{@code processingDateFrom}. This
 * resolver is the preflight: a unitemporal object whose single axis is called
 * {@code validDate} is bound to {@code validDateFrom}/{@code validDateTo}; a bitemporal
 * object that does not use the conventional pair is refused by name before any write.
 */
public final class TemporalAttributeNames {

    public static final String BUSINESS_FROM = "businessDateFrom";
    public static final String BUSINESS_TO = "businessDateTo";
    public static final String PROCESSING_FROM = "processingDateFrom";
    public static final String PROCESSING_TO = "processingDateTo";
    public static final String BUSINESS_AS_OF = "businessDate";
    public static final String PROCESSING_AS_OF = "processingDate";

    private TemporalAttributeNames() {
    }

    public static void requireResolvable(EntityMapping mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("entity mapping is required");
        }
        if (mapping.temporal().flavour() == TemporalMapping.Flavour.NONE) {
            return;
        }
        if (mapping.temporal().hasBusinessDate()) {
            businessFromJavaName(mapping);
            businessToJavaName(mapping);
        }
        if (mapping.temporal().hasProcessingDate()) {
            processingFromJavaName(mapping);
            processingToJavaName(mapping);
        }
    }

    public static String businessFromJavaName(EntityMapping mapping) {
        return resolve(mapping, true, true);
    }

    public static String businessToJavaName(EntityMapping mapping) {
        return resolve(mapping, true, false);
    }

    public static String processingFromJavaName(EntityMapping mapping) {
        return resolve(mapping, false, true);
    }

    public static String processingToJavaName(EntityMapping mapping) {
        return resolve(mapping, false, false);
    }

    public static String businessAsOfJavaName(EntityMapping mapping) {
        return asOfName(businessFromJavaName(mapping));
    }

    public static String processingAsOfJavaName(EntityMapping mapping) {
        return asOfName(processingFromJavaName(mapping));
    }

    private static String resolve(EntityMapping mapping, boolean business, boolean from) {
        TemporalMapping temporal = mapping.temporal();
        if (business && !temporal.hasBusinessDate()) {
            return null;
        }
        if (!business && !temporal.hasProcessingDate()) {
            return null;
        }
        String conventional = conventional(business, from);
        if (hasAttribute(mapping, conventional)) {
            return conventional;
        }
        if (temporal.flavour() == TemporalMapping.Flavour.BITEMPORAL) {
            throw MappingValidator.unsupportedTemporalAxis(
                    mapping.className(), foundAxes(mapping));
        }
        AttributeMapping unique = uniqueBoundary(mapping, from ? "From" : "To");
        return unique.javaName();
    }

    private static String conventional(boolean business, boolean from) {
        if (business) {
            return from ? BUSINESS_FROM : BUSINESS_TO;
        }
        return from ? PROCESSING_FROM : PROCESSING_TO;
    }

    private static AttributeMapping uniqueBoundary(EntityMapping mapping, String suffix) {
        List<AttributeMapping> matches = new ArrayList<AttributeMapping>();
        for (int i = 0; i < mapping.attributes().size(); i++) {
            AttributeMapping attribute = mapping.attributes().get(i);
            if (attribute.isPrimaryKey()) {
                continue;
            }
            if (!isTimestamp(attribute.javaType())) {
                continue;
            }
            if (attribute.javaName().endsWith(suffix)) {
                matches.add(attribute);
            }
        }
        if (matches.size() != 1) {
            throw MappingValidator.unsupportedTemporalAxis(
                    mapping.className(), foundAxes(mapping));
        }
        return matches.get(0);
    }

    private static boolean hasAttribute(EntityMapping mapping, String javaName) {
        try {
            mapping.attribute(javaName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isTimestamp(String javaType) {
        return "Timestamp".equals(javaType) || "java.sql.Timestamp".equals(javaType);
    }

    private static String asOfName(String fromJavaName) {
        if (fromJavaName != null && fromJavaName.endsWith("From")) {
            return fromJavaName.substring(0, fromJavaName.length() - 4);
        }
        return fromJavaName;
    }

    private static String foundAxes(EntityMapping mapping) {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < mapping.attributes().size(); i++) {
            AttributeMapping attribute = mapping.attributes().get(i);
            if (attribute.isPrimaryKey() || !isTimestamp(attribute.javaType())) {
                continue;
            }
            if (attribute.javaName().endsWith("From")) {
                names.add(asOfName(attribute.javaName()));
            }
        }
        if (names.isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(names.get(i));
        }
        return sb.toString();
    }
}
