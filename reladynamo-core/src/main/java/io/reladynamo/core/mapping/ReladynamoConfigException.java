package io.reladynamo.core.mapping;

/**
 * Unchecked startup / configuration failure. The message always starts with a
 * {@code RELADYNAMO-CFG-NNN} code so logs and tests can pin the exact contract.
 */
public final class ReladynamoConfigException extends RuntimeException {

    private final String code;

    public ReladynamoConfigException(String code, String message) {
        super(message);
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("error code is required");
        }
        this.code = code;
    }

    public ReladynamoConfigException(String code, String message, Throwable cause) {
        super(message, cause);
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("error code is required");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
