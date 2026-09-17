package io.reladynamo.ddb.codec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * {@code Map<String, Object>} of Reladomo attribute values ⇄ DynamoDB item, driven by an
 * {@link EntityMapping}.
 *
 * <p>Null payload attributes are stored as DynamoDB {@code NULL} ({@code {"NULL": true}}),
 * never as a typed zero or {@code false}. Empty string is stored as {@code S:""}, so empty
 * and null round-trip as distinct values. A missing attribute on decode still reconstructs
 * Java {@code null} so a later schema version can add a nullable field.
 * Every item carries {@code _rd_v}. Timestamps go through
 * {@link io.reladynamo.core.temporal.TemporalEncoder}.
 */
public final class ItemCodec {

    public static final String SCHEMA_VERSION_ATTR = "_rd_v";
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;
    public static final int MAX_SUPPORTED_SCHEMA_VERSION = CURRENT_SCHEMA_VERSION;
    public static final int MAX_ITEM_SIZE_BYTES = 400 * 1024;

    private final EntityMapping mapping;
    private final int schemaVersion;
    private final AttributeValueCodec values;
    private final ItemSizeEstimator estimator;
    private final ExpressionNames expressionNames;
    private final Map<String, AttributeKind> kinds;

    /**
     * Dispatch a version transformation before attribute decode. The supported range is currently
     * a single version, so this is identity; unknown versions are refused before this is called.
     */
    private void decodeForVersion(int schemaVersionOnItem) {
        // no transformations yet
    }

    public ItemCodec(EntityMapping mapping) {
        this(mapping, CURRENT_SCHEMA_VERSION);
    }

    public ItemCodec(EntityMapping mapping, int schemaVersion) {
        if (mapping == null) {
            throw new IllegalArgumentException("entity mapping is required");
        }
        if (schemaVersion < MIN_SUPPORTED_SCHEMA_VERSION
                || schemaVersion > MAX_SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion must be in [" + MIN_SUPPORTED_SCHEMA_VERSION
                            + "," + MAX_SUPPORTED_SCHEMA_VERSION + "], got " + schemaVersion);
        }
        this.mapping = mapping;
        this.schemaVersion = schemaVersion;
        this.values = new AttributeValueCodec(mapping.temporal().infinity());
        this.estimator = new ItemSizeEstimator();
        this.expressionNames = ExpressionNames.forMapping(mapping);
        Map<String, AttributeKind> parsed = new LinkedHashMap<>();
        for (AttributeMapping attribute : mapping.attributes()) {
            parsed.put(attribute.javaName(), AttributeKind.parse(attribute.javaType()));
        }
        this.kinds = Collections.unmodifiableMap(parsed);
    }

    public EntityMapping mapping() {
        return mapping;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /**
     * Encode domain values keyed by Java attribute name. Null nullable attributes are written
     * as DynamoDB {@code NULL}. Always writes {@code _rd_v}. Throws
     * {@link ItemTooLargeException} before a 400 KB item would hit the network.
     */
    public Map<String, AttributeValue> encode(Map<String, Object> attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("attributes is required");
        }
        for (String key : attributes.keySet()) {
            if (!kinds.containsKey(key)) {
                throw new CodecException(
                        "unknown attribute '" + key + "' on " + mapping.className()
                                + "; known: " + kinds.keySet());
            }
        }

        Map<String, AttributeValue> item =
                new LinkedHashMap<>(mapping.attributes().size() * 2);
        item.put(SCHEMA_VERSION_ATTR,
                AttributeValue.builder().n(Integer.toString(schemaVersion)).build());

        for (AttributeMapping attribute : mapping.attributes()) {
            Object value = attributes.get(attribute.javaName());
            if (value == null) {
                if (attribute.isPrimaryKey()) {
                    throw new CodecException(
                            "primary key attribute " + attribute.javaName()
                                    + " of " + mapping.className() + " cannot be null");
                }
                if (!attribute.isNullable()) {
                    throw new CodecException(
                            "non-nullable attribute " + attribute.javaName()
                                    + " of " + mapping.className() + " cannot be null");
                }
                item.put(attribute.itemName(), AttributeValue.builder().nul(true).build());
                continue;
            }
            AttributeKind kind = kinds.get(attribute.javaName());
            if (attribute.isPrimaryKey()
                    && kind == AttributeKind.STRING
                    && value instanceof String
                    && ((String) value).isEmpty()) {
                throw new CodecException(
                        "primary key attribute " + attribute.javaName()
                                + " of " + mapping.className()
                                + " cannot be empty; DynamoDB forbids empty string keys");
            }
            item.put(attribute.itemName(), values.encode(attribute, kind, value));
        }

        rejectIfTooLarge(item, attributes);
        return item;
    }

    /**
     * Enforce DynamoDB's 400 KB limit on a fully assembled stored item — payload, {@code pk},
     * {@code sk}, {@code _rd_v}, and any stamped GSI key attributes. The codec payload check
     * alone is not sufficient because the writer appends keys afterwards.
     */
    public void rejectIfTooLarge(Map<String, AttributeValue> item, Map<String, Object> attributes) {
        if (item == null) {
            throw new IllegalArgumentException("item is required");
        }
        int size = estimator.estimate(item);
        if (size > MAX_ITEM_SIZE_BYTES) {
            throw new ItemTooLargeException(
                    mapping.className(), formatPrimaryKey(attributes), size);
        }
    }

    /**
     * Decode a DynamoDB item into Java values keyed by Java attribute name. Missing attributes
     * become Java {@code null} when the mapping says nullable; otherwise this is a corrupt item.
     */
    public Map<String, Object> decode(Map<String, AttributeValue> item) {
        if (item == null) {
            throw new IllegalArgumentException("item is required");
        }
        AttributeValue version = item.get(SCHEMA_VERSION_ATTR);
        if (version == null || version.n() == null) {
            throw new CodecException(
                    "item is missing required schema version attribute " + SCHEMA_VERSION_ATTR);
        }
        int schemaVersionOnItem;
        try {
            schemaVersionOnItem = Integer.parseInt(version.n());
        } catch (NumberFormatException ex) {
            throw new CodecException("invalid " + SCHEMA_VERSION_ATTR + ": " + version.n(), ex);
        }
        if (schemaVersionOnItem < MIN_SUPPORTED_SCHEMA_VERSION
                || schemaVersionOnItem > MAX_SUPPORTED_SCHEMA_VERSION) {
            throw new UnsupportedSchemaVersionException(
                    schemaVersionOnItem, MIN_SUPPORTED_SCHEMA_VERSION, MAX_SUPPORTED_SCHEMA_VERSION);
        }
        decodeForVersion(schemaVersionOnItem);

        Map<String, Object> decoded = new LinkedHashMap<>(mapping.attributes().size() * 2);
        for (AttributeMapping attribute : mapping.attributes()) {
            AttributeValue av = item.get(attribute.itemName());
            if (av == null || Boolean.TRUE.equals(av.nul())) {
                if (attribute.isPrimaryKey()) {
                    throw new CodecException(
                            "item is missing primary key " + attribute.itemName()
                                    + " for " + mapping.className());
                }
                if (!attribute.isNullable()) {
                    throw new CodecException(
                            "item is missing non-nullable attribute " + attribute.itemName()
                                    + " for " + mapping.className());
                }
                decoded.put(attribute.javaName(), null);
                continue;
            }
            AttributeKind kind = kinds.get(attribute.javaName());
            decoded.put(attribute.javaName(), values.decode(attribute, kind, av));
        }
        return decoded;
    }

    public int estimateSize(Map<String, AttributeValue> item) {
        return estimator.estimate(item);
    }

    /**
     * Placeholder → real-name map for every mapped attribute plus {@code _rd_v}. Always used;
     * never "escape only if reserved".
     */
    public Map<String, String> expressionAttributeNames() {
        return expressionNames.asMap();
    }

    public ExpressionNames expressionNames() {
        return expressionNames;
    }

    private String formatPrimaryKey(Map<String, Object> attributes) {
        Map<String, Object> values = attributes == null ? Collections.emptyMap() : attributes;
        StringBuilder sb = new StringBuilder();
        for (AttributeMapping pk : mapping.primaryKeyAttributes()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(pk.javaName()).append('=');
            Object value = values.get(pk.javaName());
            sb.append(value == null ? "<null>" : String.valueOf(value));
        }
        return sb.toString();
    }
}
