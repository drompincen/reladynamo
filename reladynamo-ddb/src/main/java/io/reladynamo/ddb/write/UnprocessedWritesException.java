package io.reladynamo.ddb.write;

import com.gs.fw.common.mithra.MithraDatabaseException;

/**
 * Thrown when a batch still has unprocessed items after the attempt budget is spent.
 *
 * <p>Extends {@link MithraDatabaseException} so callers see Reladomo's exception contract rather
 * than an AWS type leaking through the adapter.
 */
public class UnprocessedWritesException extends MithraDatabaseException {

    private final int remaining;

    public UnprocessedWritesException(String message, int remaining) {
        super(message);
        this.remaining = remaining;
    }

    public int remaining() {
        return remaining;
    }
}
