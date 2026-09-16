package io.reladynamo.core.config;

/** One Reladomo attribute and the DynamoDB item attribute it is stored as. */
public final class AttributeMapping {

    private final String javaName;
    private final String itemName;
    private final String javaType;
    private final boolean primaryKey;
    private final boolean nullable;

    public AttributeMapping(String javaName, String itemName, String javaType,
                            boolean primaryKey, boolean nullable) {
        if (javaName == null || javaName.isEmpty()) {
            throw new IllegalArgumentException("javaName is required");
        }
        if (itemName == null || itemName.isEmpty()) {
            throw new IllegalArgumentException("itemName is required for " + javaName);
        }
        if (javaType == null || javaType.isEmpty()) {
            throw new IllegalArgumentException("javaType is required for " + javaName);
        }
        if (primaryKey && nullable) {
            throw new IllegalArgumentException(
                    "primary key attribute cannot be nullable: " + javaName);
        }
        this.javaName = javaName;
        this.itemName = itemName;
        this.javaType = javaType;
        this.primaryKey = primaryKey;
        this.nullable = nullable;
    }

    public String javaName() {
        return javaName;
    }

    public String itemName() {
        return itemName;
    }

    public String javaType() {
        return javaType;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public boolean isNullable() {
        return nullable;
    }
}
