package io.reladynamo.core.key;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.temporal.TemporalEncoder;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Map;

/**
 * Default v1 key grammar. Timestamps go through {@link TemporalEncoder}; they are never
 * ISO-8601 in the key.
 */
public final class DefaultKeyStrategy implements KeyStrategy {

    public static final int MAX_PARTITION_KEY_BYTES = 2048;
    public static final int MAX_SORT_KEY_BYTES = 1024;

    /** Sort key for entities with no AsOfAttribute; keeps the key schema uniform across tables. */
    public static final String NON_DATED_SORT_KEY = "v1#ND";

    @Override
    public String partitionKey(EntityMapping mapping, Map<String, ?> pkValues) {
        if (mapping == null) {
            throw new IllegalArgumentException("entity mapping is required");
        }
        if (pkValues == null) {
            throw new IllegalArgumentException("primary-key values are required for " + mapping.className());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("v1#").append(simpleClassName(mapping.className()).toUpperCase(java.util.Locale.ROOT));
        for (AttributeMapping pk : mapping.primaryKeyAttributes()) {
            Object value = pkValues.get(pk.javaName());
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing primary-key value for " + mapping.className() + "." + pk.javaName()
                                + ": a partition-key component is never optional");
            }
            sb.append('#').append(KeyComponentEncoder.encode(mapping, pk, value));
        }
        String key = sb.toString();
        checkLimit(mapping.className(), "partition key", key, MAX_PARTITION_KEY_BYTES);
        return key;
    }

    @Override
    public String sortKey(EntityMapping mapping, Timestamp processingDateFrom, Timestamp businessDateFrom) {
        if (mapping == null) {
            throw new IllegalArgumentException("entity mapping is required");
        }
        TemporalMapping.Flavour flavour = mapping.temporal().flavour();
        if (flavour == TemporalMapping.Flavour.NONE) {
            // A constant sort key rather than none, so every table shares one key schema. Without it
            // table creation, the executor and the planner each have to special-case non-dated
            // entities, and that branch is where inconsistencies breed.
            return NON_DATED_SORT_KEY;
        }
        StringBuilder sb = new StringBuilder("v1");
        if (flavour == TemporalMapping.Flavour.BITEMPORAL || flavour == TemporalMapping.Flavour.AUDIT_ONLY) {
            requireTimestamp(mapping, "processingDateFrom", processingDateFrom);
            sb.append("#P#").append(TemporalEncoder.encode(processingDateFrom));
        }
        if (flavour == TemporalMapping.Flavour.BITEMPORAL || flavour == TemporalMapping.Flavour.BUSINESS_ONLY) {
            requireTimestamp(mapping, "businessDateFrom", businessDateFrom);
            sb.append("#B#").append(TemporalEncoder.encode(businessDateFrom));
        }
        String key = sb.toString();
        checkLimit(mapping.className(), "sort key", key, MAX_SORT_KEY_BYTES);
        return key;
    }

    static void checkLimit(String entity, String kind, String value, int maxBytes) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw new IllegalArgumentException(
                    kind + " for " + entity + " exceeds " + maxBytes + " bytes: " + value);
        }
    }

    private static void requireTimestamp(EntityMapping mapping, String axis, Timestamp value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing " + axis + " for " + mapping.className()
                            + ": a temporal key component is never optional");
        }
    }

    private static String simpleClassName(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
