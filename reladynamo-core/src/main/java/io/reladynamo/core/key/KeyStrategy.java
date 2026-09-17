package io.reladynamo.core.key;

import io.reladynamo.core.config.EntityMapping;

import java.sql.Timestamp;
import java.util.Map;

/**
 * Builds DynamoDB partition and sort keys from an {@link EntityMapping} and row values.
 *
 * <p>Grammar (version {@code v1}):
 * <ul>
 *   <li>partition: {@code v1#<CLASS>#<pk1>#<pk2>…}</li>
 *   <li>bitemporal sort: {@code v1#P#<processingDateFrom>#B#<businessDateFrom>}</li>
 *   <li>non-temporal ({@code Flavour.NONE}): no sort key ({@code null})</li>
 * </ul>
 */
public interface KeyStrategy {

    String partitionKey(EntityMapping mapping, Map<String, ?> pkValues);

    /**
     * @return the sort key, or {@code null} when the entity has {@code Flavour.NONE}
     */
    String sortKey(EntityMapping mapping, Timestamp processingDateFrom, Timestamp businessDateFrom);
}
