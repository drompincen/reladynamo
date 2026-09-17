package io.reladynamo.ddb.persist;

/** Deliberately non-retriable by Reladomo: never replay a whole logical command after ambiguity. */
public class DynamoDbTransactionException extends RuntimeException {

    private final String code;

    public DynamoDbTransactionException(String code, String message) {
        this(code, message, null);
    }

    public DynamoDbTransactionException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
