package io.reladynamo.ddb.migrate;

/**
 * Durable persistence for {@link BackfillCheckpoint}. Implementations must survive process death.
 */
public interface BackfillCheckpointStore {

    void save(BackfillCheckpoint checkpoint);

    /** {@code null} when no checkpoint has been written yet. */
    BackfillCheckpoint load();
}
