package io.reladynamo.ddb.codec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Always-on {@code ExpressionAttributeNames} map for one entity.
 *
 * <p>DynamoDB reserved words include tokens Reladomo models use constantly ({@code name},
 * {@code status}, {@code size}, {@code type}, …). Escaping only when a name is on today's
 * reserved list fails the day AWS adds another. Every user-attribute path is aliased.
 */
public final class ExpressionNames {

    private final Map<String, String> placeholderToName;
    private final Map<String, String> nameToPlaceholder;

    private ExpressionNames(Map<String, String> placeholderToName,
                            Map<String, String> nameToPlaceholder) {
        this.placeholderToName = placeholderToName;
        this.nameToPlaceholder = nameToPlaceholder;
    }

    public static ExpressionNames forMapping(EntityMapping mapping) {
        Map<String, String> placeholderToName = new LinkedHashMap<>();
        Map<String, String> nameToPlaceholder = new LinkedHashMap<>();
        bind(placeholderToName, nameToPlaceholder, "#rd_v", ItemCodec.SCHEMA_VERSION_ATTR);
        int index = 0;
        for (AttributeMapping attribute : mapping.attributes()) {
            if (nameToPlaceholder.containsKey(attribute.itemName())) {
                continue;
            }
            bind(placeholderToName, nameToPlaceholder, "#a" + index, attribute.itemName());
            index++;
        }
        return new ExpressionNames(
                Collections.unmodifiableMap(placeholderToName),
                Collections.unmodifiableMap(nameToPlaceholder));
    }

    private static void bind(Map<String, String> placeholderToName,
                             Map<String, String> nameToPlaceholder,
                             String placeholder, String itemName) {
        placeholderToName.put(placeholder, itemName);
        nameToPlaceholder.put(itemName, placeholder);
    }

    /** Placeholder such as {@code #a0} for a stored item attribute name. */
    public String placeholder(String itemName) {
        String placeholder = nameToPlaceholder.get(itemName);
        if (placeholder == null) {
            throw new CodecException("no ExpressionAttributeNames entry for " + itemName);
        }
        return placeholder;
    }

    /**
     * The map to pass as {@code expressionAttributeNames} on Query/Update/Condition. Keys are
     * placeholders ({@code #a0}); values are the real attribute names.
     */
    public Map<String, String> asMap() {
        return placeholderToName;
    }
}
