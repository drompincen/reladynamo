package io.reladynamo.core.mapping;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup validation for XML-derived mappings and the design's CFG error catalogue.
 *
 * <p>Messages are an exact contract ({@code docs/design/01-xml-to-ddb-mapping.md} §3.5).
 */
public final class MappingValidator {

    private MappingValidator() {
    }

    public static void validate(EntityMapping mapping) {
        validate(mapping, Collections.emptyList());
    }

    public static void validate(EntityMapping mapping, Collection<String> extraReservedItemNames) {
        if (mapping == null) {
            throw new IllegalArgumentException("entity mapping is required");
        }
        if (mapping.primaryKeyAttributes().isEmpty()) {
            throw missingPrimaryKey(mapping.className());
        }
        if (extraReservedItemNames == null) {
            throw new IllegalArgumentException("extra reserved item names are required");
        }
        validateItemNamespace(mapping.className(), mapping.attributes(), extraReservedItemNames);
        TemporalAttributeNames.requireResolvable(mapping);
        for (int i = 0; i < mapping.primaryKeyAttributes().size(); i++) {
            AttributeMapping pk = mapping.primaryKeyAttributes().get(i);
            requireSupportedPrimaryKeyType(mapping.className(), pk.javaName(), pk.javaType());
        }
    }

    /**
     * Item names must be unique and disjoint from {@code pk}, {@code sk}, {@code _rd_v},
     * and any extra reserved names (GSI key attributes).
     */
    public static void validateItemNamespace(String className, List<AttributeMapping> attributes,
                                             Collection<String> extraReservedItemNames) {
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("className is required");
        }
        if (attributes == null) {
            throw new IllegalArgumentException("attributes are required for " + className);
        }
        Collection<String> extra = extraReservedItemNames == null
                ? Collections.emptyList()
                : extraReservedItemNames;
        Map<String, String> itemToJava = new LinkedHashMap<>();
        for (AttributeMapping attribute : attributes) {
            String previous = itemToJava.put(attribute.itemName(), attribute.javaName());
            if (previous != null) {
                throw duplicateItemName(className, previous, attribute.javaName(), attribute.itemName());
            }
            if (isReserved(attribute.itemName(), extra)) {
                throw reservedItemName(className, attribute.javaName(), attribute.itemName());
            }
        }
    }

    public static ReladynamoConfigException reservedItemName(String objectName, String javaName,
                                                             String itemName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-013",
                "RELADYNAMO-CFG-013: Object " + objectName
                        + " maps attribute '" + javaName
                        + "' to reserved storage name '" + itemName
                        + "'; item names must be unique and disjoint from pk, sk, _rd_v, and every GSI key attribute.");
    }

    public static ReladynamoConfigException duplicateItemName(String objectName, String firstJava,
                                                              String secondJava, String itemName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-013",
                "RELADYNAMO-CFG-013: Object " + objectName
                        + " maps attributes '" + firstJava + "' and '" + secondJava
                        + "' to the same item name '" + itemName
                        + "'; item names must be unique and disjoint from pk, sk, _rd_v, and every GSI key attribute.");
    }

    private static boolean isReserved(String itemName, Collection<String> extraReservedItemNames) {
        if ("pk".equals(itemName) || "sk".equals(itemName) || "_rd_v".equals(itemName)) {
            return true;
        }
        for (String extra : extraReservedItemNames) {
            if (itemName.equals(extra)) {
                return true;
            }
        }
        return false;
    }

    public static ReladynamoConfigException missingPrimaryKey(String objectName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-001",
                "RELADYNAMO-CFG-001: Object " + objectName
                        + " has no primaryKey=\"true\" attribute; DynamoDB partition key cannot be derived.");
    }

    public static ReladynamoConfigException mithraPureObject(String objectName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-002",
                "RELADYNAMO-CFG-002: MithraPureObject " + objectName
                        + " cannot be bound to DynamoDB; use MithraPureObjectFactory or convert to MithraObject.");
    }

    public static ReladynamoConfigException infinityIsNull(String objectName, String asOfName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-003",
                "RELADYNAMO-CFG-003: Object " + objectName
                        + " AsOfAttribute " + asOfName
                        + " sets infinityIsNull=true; Reladynamo requires a concrete infinity Timestamp"
                        + " for lexicographic sort keys.");
    }

    public static ReladynamoConfigException identityColumn(String objectName, String attributeName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-004",
                "RELADYNAMO-CFG-004: Attribute " + objectName + "." + attributeName
                        + " has identity=\"true\"; DynamoDB has no identity columns. Use"
                        + " primaryKeyGeneratorStrategy=\"SimulatedSequence\" or assign keys in application code.");
    }

    public static ReladynamoConfigException unknownGsiAttribute(
            String gsiName, String objectName, String attributeName, Collection<String> known) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-005",
                "RELADYNAMO-CFG-005: reladynamo.xml Gsi '" + gsiName + "' on " + objectName
                        + " references attribute '" + attributeName
                        + "' which does not exist on the MithraObject model (known: "
                        + join(known) + ").");
    }

    public static void rejectUnknownGsiAttribute(
            String gsiName, String objectName, String attributeName, Collection<String> known) {
        throw unknownGsiAttribute(gsiName, objectName, attributeName, known);
    }

    public static ReladynamoConfigException provisionedCapacityMissing(String tableName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-006",
                "RELADYNAMO-CFG-006: Table " + tableName
                        + " billingMode=PROVISIONED but readCapacityUnits/writeCapacityUnits are missing or ≤ 0.");
    }

    public static ReladynamoConfigException ttlNotNumber(String ttlAttribute, String objectName, String javaType) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-007",
                "RELADYNAMO-CFG-007: Ttl attribute '" + ttlAttribute + "' on " + objectName
                        + " must map to a Number (epoch seconds); javaType=" + javaType + " is invalid.");
    }

    public static ReladynamoConfigException hashInKeyAttribute(String objectName, String attributeName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-008",
                "RELADYNAMO-CFG-008: Attribute " + objectName + "." + attributeName
                        + " is part of the DynamoDB partition key and allows values containing '#';"
                        + " forbid '#' in key attributes or supply a Keys escapeStrategy.");
    }

    public static ReladynamoConfigException writeAmplification(String objectName, int gsiCount) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-009",
                "RELADYNAMO-CFG-009: Object " + objectName + " derives " + gsiCount
                        + " GSIs; set DynamoDefaults acknowledgeWriteAmplification=\"true\" after reviewing"
                        + " write cost, or remove unused Gsi entries.");
    }

    public static ReladynamoConfigException sortKeyOrderMismatch(
            String objectName, String requested, String tableName, String tagged) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-010",
                "RELADYNAMO-CFG-010: PhysicalDesign for " + objectName
                        + " requests sortKeyTemporalOrder=" + requested
                        + " but table " + tableName + " is tagged rd:skOrder=" + tagged
                        + "; key grammar changes require a new table migration (see evolution matrix).");
    }

    public static ReladynamoConfigException mithraTempObject(String objectName) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-002",
                "RELADYNAMO-CFG-002: MithraTempObject " + objectName
                        + " cannot be bound to DynamoDB; temporary objects are not a durable store contract.");
    }

    public static ReladynamoConfigException xmlParseFailure(String detail, Throwable cause) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-001",
                "RELADYNAMO-CFG-001: XML could not be parsed: " + detail,
                cause);
    }

    private static String join(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String value : values) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(value);
        }
        return sb.toString();
    }
    /**
     * Reladomo's {@code sourceAttribute} routes objects to different physical databases — it is how
     * multi-tenancy is expressed. The adapter has no concept of it: it is not in the key, and the
     * planner cannot constrain it.
     *
     * <p>Accepting such a model silently would put every tenant's rows in one table with no
     * discriminator in the key, so a query for one tenant would return all of them. That is a
     * data-isolation failure that no test of a single-tenant model would ever surface, which is
     * exactly why it must fail at configuration time.
     */
    public static ReladynamoConfigException sourceAttributeUnsupported(String fqcn, String name) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-012",
                "RELADYNAMO-CFG-012: Object " + fqcn + " declares a sourceAttribute '" + name
                        + "'. Reladynamo does not support sourceAttribute routing: the value is not "
                        + "part of the partition key and the planner cannot constrain it, so rows "
                        + "from every source would share one table and a query for one source would "
                        + "return all of them. Refusing rather than silently merging tenants.");
    }

    /**
     * {@code KEYS_ONLY} and {@code INCLUDE} GSIs return a partial item. The executor decodes
     * GSI results as full rows and does not hydrate missing attributes from the base table,
     * so omitted fields would reconstruct as Java null. Refuse at startup rather than lie.
     */
    public static ReladynamoConfigException unsupportedGsiProjection(
            String objectName, String gsiName, String projection) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-014",
                "RELADYNAMO-CFG-014: Object " + objectName + " GSI '" + gsiName
                        + "' uses projection " + projection
                        + "; Reladynamo does not hydrate missing attributes from the base table, "
                        + "so KEYS_ONLY and INCLUDE would reconstruct omitted fields as null. "
                        + "Use Projection.ALL, or omit the GSI.");
    }

    /**
     * Bitemporal objects must use Reladomo's conventional {@code businessDate}/{@code processingDate}
     * names because {@link io.reladynamo.core.config.TemporalMapping} has no slot for axis names.
     * A single unitemporal axis with a custom name (for example {@code validDate}) is resolved
     * from the unique From/To pair. Anything else would have been written under
     * {@code businessDateFrom} that the mapping does not contain.
     */
    public static ReladynamoConfigException unsupportedTemporalAxis(String objectName, String found) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-015",
                "RELADYNAMO-CFG-015: Object " + objectName
                        + " declares AsOfAttribute names that Reladynamo cannot bind to its temporal "
                        + "storage contract (found: " + found + "). Bitemporal objects must use "
                        + "businessDate and processingDate; a single non-conventional axis (for "
                        + "example validDate) is supported for unitemporal objects. Refusing rather "
                        + "than writing rows under businessDateFrom/processingDateFrom that the "
                        + "mapping does not contain.");
    }

    public static ReladynamoConfigException unsupportedPrimaryKeyType(
            String objectName, String attributeName, String javaType) {
        return new ReladynamoConfigException(
                "RELADYNAMO-CFG-016",
                "RELADYNAMO-CFG-016: Attribute " + objectName + "." + attributeName
                        + " has javaType=" + javaType
                        + " which is not a supported primary-key type; Reladynamo partition-key "
                        + "components must be String, boolean, byte/short/int/long, char, "
                        + "Timestamp, Date, Time, BigDecimal, or byte[].");
    }

    public static void requireSupportedPrimaryKeyType(String objectName, String attributeName,
                                                     String javaType) {
        if (!isSupportedPrimaryKeyType(javaType)) {
            throw unsupportedPrimaryKeyType(objectName, attributeName, javaType);
        }
    }

    private static boolean isSupportedPrimaryKeyType(String javaType) {
        if (javaType == null) {
            return false;
        }
        return "String".equals(javaType)
                || "boolean".equals(javaType) || "Boolean".equals(javaType)
                || "byte".equals(javaType) || "Byte".equals(javaType)
                || "short".equals(javaType) || "Short".equals(javaType)
                || "int".equals(javaType) || "Integer".equals(javaType)
                || "long".equals(javaType) || "Long".equals(javaType)
                || "char".equals(javaType) || "Character".equals(javaType)
                || "Timestamp".equals(javaType) || "java.sql.Timestamp".equals(javaType)
                || "Date".equals(javaType) || "java.sql.Date".equals(javaType)
                || "Time".equals(javaType) || "java.sql.Time".equals(javaType)
                || "com.gs.fw.common.mithra.util.Time".equals(javaType)
                || "BigDecimal".equals(javaType) || "java.math.BigDecimal".equals(javaType)
                || "byte[]".equals(javaType);
    }

}
