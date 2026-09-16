package io.reladynamo.ddb.exec;

/**
 * Result of comparing a requested physical design against the table that actually exists.
 *
 * <p>Requested-versus-actual is a checked invariant, not an assumption that {@code ACTIVE}
 * means "ready to serve this mapping".
 */
public enum SchemaReconcileOutcome {
    /** No table existed; it was created with the requested key schema and indexes. */
    CREATE,
    /** Existing table key schema and requested GSIs match; no mutation. */
    VALIDATE,
    /** Key schema is compatible; a requested GSI was missing and has been added. */
    ADD_INDEX,
    /**
     * A requested GSI is missing and existing items lack its key attributes. Adding the
     * index now would be a sparse no-op over those rows — the caller must stamp keys first.
     */
    BACKFILL_INDEX_KEY,
    /** Base key schema (or an existing GSI's keys) differs from the request. Refuse. */
    INCOMPATIBLE
}
