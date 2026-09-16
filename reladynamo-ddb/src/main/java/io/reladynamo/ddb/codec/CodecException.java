package io.reladynamo.ddb.codec;

/**
 * Unchecked failure while encoding or decoding a DynamoDB item. Named so callers can
 * distinguish codec problems from service errors.
 */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
