package io.reladynamo.ddb.migrate;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Durable record of a backfill that can be killed and resumed.
 *
 * <p>{@code snapshotId} is a caller-supplied stable identifier for the source (query digest,
 * extract timestamp, dump filename). A resumed run with a different id is refused: that is
 * not the same source.
 *
 * <p>{@code watermark} is the partition identity of the last completed logical key.
 * {@code completedPartitions} is the set of keys whose write+verify has been persisted;
 * an incomplete partition is absent, so resume redoes it and does not skip it.
 *
 * <p>Java 11 baseline.
 */
public final class BackfillCheckpoint {

    private final String snapshotId;
    private final String watermark;
    private final Set<String> completedPartitions;
    private final int rowsRead;
    private final int rowsWritten;

    public BackfillCheckpoint(String snapshotId, String watermark, Set<String> completedPartitions,
                              int rowsRead, int rowsWritten) {
        if (snapshotId == null || snapshotId.isEmpty()) {
            throw new IllegalArgumentException("snapshotId is required");
        }
        if (completedPartitions == null) {
            throw new IllegalArgumentException("completedPartitions is required");
        }
        if (rowsRead < 0 || rowsWritten < 0) {
            throw new IllegalArgumentException("row counts cannot be negative");
        }
        this.snapshotId = snapshotId;
        this.watermark = watermark == null ? "" : watermark;
        this.completedPartitions = Collections.unmodifiableSet(new LinkedHashSet<String>(completedPartitions));
        this.rowsRead = rowsRead;
        this.rowsWritten = rowsWritten;
    }

    public String snapshotId() {
        return snapshotId;
    }

    public String watermark() {
        return watermark;
    }

    public Set<String> completedPartitions() {
        return completedPartitions;
    }

    public int rowsRead() {
        return rowsRead;
    }

    public int rowsWritten() {
        return rowsWritten;
    }
}
