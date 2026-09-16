package io.reladynamo.core.config;

import io.reladynamo.core.mapping.MappingValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The physical DynamoDB design for one Reladomo entity, derived from its object model XML.
 *
 * <p>Topology is <b>table-per-object</b>, not classic single-table: Reladomo's finders are
 * per-object, so a shared table buys no join locality and costs index clarity. See
 * {@code docs/design/01-xml-to-ddb-mapping.md}.
 */
public final class EntityMapping {

    private final String className;
    private final String tableName;
    private final TemporalMapping temporal;
    private final List<AttributeMapping> attributes;
    private final Map<String, AttributeMapping> byJavaName;
    private final List<AttributeMapping> primaryKey;

    public EntityMapping(String className, String tableName, TemporalMapping temporal,
                         List<AttributeMapping> attributes) {
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("className is required");
        }
        if (tableName == null || tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName is required for " + className);
        }
        if (temporal == null) {
            throw new IllegalArgumentException("temporal mapping is required for " + className);
        }
        if (attributes == null || attributes.isEmpty()) {
            throw new IllegalArgumentException("at least one attribute is required for " + className);
        }
        this.className = className;
        this.tableName = tableName;
        this.temporal = temporal;

        List<AttributeMapping> copy = new ArrayList<>(attributes);
        Map<String, AttributeMapping> index = new LinkedHashMap<>();
        List<AttributeMapping> pk = new ArrayList<>();
        for (AttributeMapping a : copy) {
            if (index.put(a.javaName(), a) != null) {
                throw new IllegalArgumentException(
                        "duplicate attribute " + a.javaName() + " on " + className);
            }
            if (a.isPrimaryKey()) {
                pk.add(a);
            }
        }
        if (pk.isEmpty()) {
            throw new IllegalArgumentException(
                    "no primary key on " + className + ": a partition key cannot be derived");
        }
        MappingValidator.validateItemNamespace(className, copy, Collections.emptyList());
        this.attributes = Collections.unmodifiableList(copy);
        this.byJavaName = Collections.unmodifiableMap(index);
        this.primaryKey = Collections.unmodifiableList(pk);
    }

    public String className() {
        return className;
    }

    public String tableName() {
        return tableName;
    }

    public TemporalMapping temporal() {
        return temporal;
    }

    public List<AttributeMapping> attributes() {
        return attributes;
    }

    /** Primary-key attributes in declaration order — the order the partition key is built from. */
    public List<AttributeMapping> primaryKeyAttributes() {
        return primaryKey;
    }

    public AttributeMapping attribute(String javaName) {
        AttributeMapping a = byJavaName.get(javaName);
        if (a == null) {
            throw new IllegalArgumentException(
                    "no attribute " + javaName + " on " + className + "; known: " + byJavaName.keySet());
        }
        return a;
    }
}
