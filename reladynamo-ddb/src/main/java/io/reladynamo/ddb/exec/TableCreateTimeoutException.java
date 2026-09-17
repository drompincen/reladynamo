package io.reladynamo.ddb.exec;

/**
 * The table did not reach {@code ACTIVE} before the configured wait expired.
 */
public final class TableCreateTimeoutException extends IllegalStateException {

    public TableCreateTimeoutException(String message) {
        super(message);
    }

    public TableCreateTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
