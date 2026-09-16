package io.reladynamo.ddb.codec;

/**
 * Thrown when an encoded item would exceed DynamoDB's 400 KB hard limit. The message
 * names the entity, the primary key and the measured size so operators can find the row.
 */
public final class ItemTooLargeException extends CodecException {

    private final String entity;
    private final String primaryKey;
    private final int measuredSizeBytes;

    public ItemTooLargeException(String entity, String primaryKey, int measuredSizeBytes) {
        super("DynamoDB item for " + entity + " with primary key [" + primaryKey + "] is "
                + measuredSizeBytes + " bytes, exceeding the 400 KB limit ("
                + (400 * 1024) + ")");
        this.entity = entity;
        this.primaryKey = primaryKey;
        this.measuredSizeBytes = measuredSizeBytes;
    }

    public String entity() {
        return entity;
    }

    public String primaryKey() {
        return primaryKey;
    }

    public int measuredSizeBytes() {
        return measuredSizeBytes;
    }
}
