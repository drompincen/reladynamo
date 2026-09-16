package io.reladynamo.ddb.migrate;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.diff.MappedRowSetDiffer;
import io.reladynamo.core.key.KeyStrategy;
import io.reladynamo.core.mapping.TemporalAttributeNames;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies an existing relational history into DynamoDB and proves the copy landed.
 *
 * <p>This is the step where a migration goes wrong quietly. A partial copy leaves DynamoDB looking
 * populated and being incomplete, and a run that reports "done" tells you nothing about whether the
 * rows are actually there. So this reads back what it wrote and diffs it, and
 * {@link BackfillResult#verified()} is false unless rows were genuinely copied and matched.
 *
 * <p>Verification compares <b>every mapped attribute</b> with type-aware equality, in both
 * directions, using {@link MappedRowSetDiffer}. Temporal-boundary-only matching is not a
 * verification. {@code rowsWritten} counts source rows that exist in the destination with
 * identical mapped attributes — not "the partition came back non-empty".
 *
 * <p><b>Destination scope.</b> Verification is scoped to the partitions of the logical keys
 * present in the source batch (the full primary key, every component). Extra versions inside
 * those partitions are divergences. Pre-existing destination rows for <em>other</em> logical
 * keys are out of scope: an incremental batch must not fail because the rest of the table
 * already holds earlier batches. A full-table migration therefore passes the full source.
 *
 * <p><b>Streaming intake (M-04).</b> {@link #run(List)} stays for callers that already have a
 * small source in hand. The operational path is {@link #run(Iterator, BackfillConfig)} /
 * {@link #run(Iterable, BackfillConfig)}. The source is never collected into one list.
 * Memory bound: at most <em>one open logical partition</em> of source rows (all versions of
 * one primary key) plus a write-staging window of {@link BackfillConfig#maxBufferedRows()}
 * (default 256). Across K keys that is O(V_max), not O(K·V). The streaming source must emit
 * all versions of a logical key contiguously (the natural result of {@code ORDER BY pk, ...}).
 *
 * <p><b>Verification complexity (M-04).</b> Source rows are grouped by logical key. Each
 * destination partition is read <em>once</em> and the group is diffed. For a key with V
 * versions that is O(V) item reads, not O(V²). See {@link BackfillResult#partitionReads()}.
 *
 * <p><b>Checkpoints (M-04).</b> After a partition is written and verified identical, its
 * identity is persisted with the source watermark and a caller-supplied snapshot identifier.
 * Resume skips completed partitions, redoes an incomplete one (idempotent upserts), and
 * refuses a different snapshot id.
 *
 * <p><b>Idempotent by construction.</b> The item key includes the temporal boundaries, so the same
 * logical row always lands on the same item. Writes go through {@link DynamoDbWriter#batchUpsert},
 * not {@link DynamoDbWriter#batchInsert}: ORM insert refuses a duplicate {@code pk+sk}, while
 * migration replay must overwrite. Re-running an interrupted migration converges rather than
 * duplicating — which matters, because migrations get interrupted.
 *
 * <p>Java 11 baseline.
 */
public final class Backfill {

    private final DynamoDbClient client;
    private final EntityMapping mapping;
    private final ItemCodec codec;
    private final KeyStrategy keys;
    private final DynamoDbWriter writer;

    public Backfill(DynamoDbClient client, EntityMapping mapping, ItemCodec codec,
                    KeyStrategy keys, DynamoDbWriter writer) {
        if (client == null || mapping == null || codec == null || keys == null || writer == null) {
            throw new IllegalArgumentException("all collaborators are required");
        }
        this.client = client;
        this.mapping = mapping;
        this.codec = codec;
        this.keys = keys;
        this.writer = writer;
    }

    /**
     * Copies every supplied row via {@link DynamoDbWriter#batchUpsert}, then reads each back
     * and compares. Callers that want duplicate detection must use the ORM {@code insert} path
     * instead of this method.
     */
    public BackfillResult run(List<Map<String, Object>> sourceRows) {
        return run(sourceRows, BackfillConfig.defaults());
    }

    public BackfillResult run(List<Map<String, Object>> sourceRows, BackfillConfig config) {
        if (sourceRows == null) {
            throw new IllegalArgumentException("source rows are required");
        }
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (sourceRows.isEmpty()) {
            return emptyResult(config);
        }
        for (int i = 0; i < sourceRows.size(); i++) {
            requireBoundaries(sourceRows.get(i));
            requireFullPrimaryKey(sourceRows.get(i));
        }
        MappedRowSetDiffer.requireUniqueIdentities(mapping, sourceRows);
        return runIterator(sourceRows.iterator(), config);
    }

    public BackfillResult run(Iterable<Map<String, Object>> sourceRows) {
        return run(sourceRows, BackfillConfig.defaults());
    }

    public BackfillResult run(Iterable<Map<String, Object>> sourceRows, BackfillConfig config) {
        if (sourceRows == null) {
            throw new IllegalArgumentException("source rows are required");
        }
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        return runIterator(sourceRows.iterator(), config);
    }

    public BackfillResult run(Iterator<Map<String, Object>> sourceRows) {
        return run(sourceRows, BackfillConfig.defaults());
    }

    public BackfillResult run(Iterator<Map<String, Object>> sourceRows, BackfillConfig config) {
        if (sourceRows == null) {
            throw new IllegalArgumentException("source rows are required");
        }
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        return runIterator(sourceRows, config);
    }

    /**
     * Re-reads the destination partitions of {@code sourceRows} and diffs every mapped attribute.
     * Does not write.
     */
    public BackfillResult verify(List<Map<String, Object>> sourceRows) {
        if (sourceRows == null) {
            throw new IllegalArgumentException("source rows are required");
        }
        if (sourceRows.isEmpty()) {
            return new BackfillResult(0, 0, Collections.<String>emptyList());
        }

        Map<String, List<Map<String, Object>>> sourceByPartition = new LinkedHashMap<String, List<Map<String, Object>>>();
        Map<String, Map<String, Object>> pkByPartition = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> row : sourceRows) {
            Map<String, Object> pk = extractPrimaryKey(row);
            String partition = MappedRowSetDiffer.partitionIdentity(mapping, pk);
            List<Map<String, Object>> group = sourceByPartition.get(partition);
            if (group == null) {
                group = new ArrayList<Map<String, Object>>();
                sourceByPartition.put(partition, group);
                pkByPartition.put(partition, pk);
            }
            group.add(row);
        }

        List<String> divergences = new ArrayList<String>();
        int written = 0;
        int partitionReads = 0;
        int peak = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : sourceByPartition.entrySet()) {
            List<Map<String, Object>> sourceForPk = e.getValue();
            peak = Math.max(peak, sourceForPk.size());
            List<Map<String, Object>> destForPk = readBack(pkByPartition.get(e.getKey()));
            partitionReads++;
            try {
                List<String> d = MappedRowSetDiffer.compare(mapping, sourceForPk, destForPk);
                divergences.addAll(d);
            } catch (IllegalArgumentException ex) {
                divergences.add(ex.getMessage());
            }
            for (Map<String, Object> source : sourceForPk) {
                if (hasIdenticalCounterpart(source, destForPk)) {
                    written++;
                }
            }
        }
        return new BackfillResult(sourceRows.size(), written, divergences, false, peak, partitionReads);
    }

    /**
     * Every stored version for one single-component primary-key value, fully paginated.
     * Composite keys must use {@link #readBack(Map)}.
     */
    public List<Map<String, Object>> readBack(Object pkValue) {
        if (mapping.primaryKeyAttributes().size() != 1) {
            throw new IllegalArgumentException(
                    mapping.className() + " has a composite primary key; pass the full key map "
                            + "to readBack(Map)");
        }
        Map<String, Object> pk = new LinkedHashMap<String, Object>();
        pk.put(mapping.primaryKeyAttributes().get(0).javaName(), pkValue);
        return readBack(pk);
    }

    /** Every stored version for one logical primary key (every component), fully paginated. */
    public List<Map<String, Object>> readBack(Map<String, Object> pkValues) {
        Map<String, Object> pk = extractPrimaryKey(pkValues);
        String partition = keys.partitionKey(mapping, pk);

        Map<String, AttributeValue> values = new HashMap<String, AttributeValue>();
        values.put(":pk", AttributeValue.builder().s(partition).build());

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        Map<String, AttributeValue> start = null;
        do {
            final Map<String, AttributeValue> from = start;
            QueryResponse r = client.query(b -> {
                b.tableName(mapping.tableName()).keyConditionExpression("pk = :pk")
                        .expressionAttributeValues(values).consistentRead(true);
                if (from != null && !from.isEmpty()) {
                    b.exclusiveStartKey(from);
                }
            });
            for (Map<String, AttributeValue> item : r.items()) {
                rows.add(codec.decode(item));
            }
            start = r.lastEvaluatedKey();
        } while (start != null && !start.isEmpty());
        return rows;
    }

    private BackfillResult runIterator(Iterator<Map<String, Object>> source, BackfillConfig config) {
        BackfillCheckpoint existing = loadCheckpoint(config);
        Set<String> completedBefore = new LinkedHashSet<String>();
        int rowsRead = 0;
        int rowsWritten = 0;
        if (existing != null) {
            completedBefore.addAll(existing.completedPartitions());
            rowsRead = existing.rowsRead();
            rowsWritten = existing.rowsWritten();
        }

        WriteRateLimiter limiter = null;
        if (config.maxWritesPerSecond() != null) {
            limiter = new WriteRateLimiter(config.maxWritesPerSecond().intValue());
        }

        Set<String> completedThisRun = new LinkedHashSet<String>();
        Set<String> seenThisRun = new LinkedHashSet<String>();
        List<Map<String, Object>> buffer = new ArrayList<Map<String, Object>>();
        String openPartition = null;
        Map<String, Object> openPk = null;
        int peak = 0;
        int partitionReads = 0;
        int completedRead = rowsRead;
        int completedWritten = rowsWritten;
        List<String> divergences = new ArrayList<String>();
        boolean seenRow = false;

        while (source.hasNext()) {
            Map<String, Object> row = source.next();
            seenRow = true;
            requireBoundaries(row);
            requireFullPrimaryKey(row);
            String part = MappedRowSetDiffer.partitionIdentity(mapping, row);

            if (seenThisRun.contains(part)) {
                throw new IllegalStateException(
                        "source is not grouped by logical key: partition '" + part
                                + "' reappeared after it was completed. All versions of a key must "
                                + "be contiguous.");
            }
            if (completedBefore.contains(part)) {
                // Completed keys need not be a prefix: an earlier key can fail verify
                // while a later one succeeds. Skip them wherever they sit.
                continue;
            }

            if (openPartition != null && !openPartition.equals(part)) {
                PartitionOutcome outcome = flushPartition(buffer, openPk, config, limiter);
                rowsRead += outcome.rowsRead;
                rowsWritten += outcome.rowsWritten;
                partitionReads += outcome.partitionReads;
                divergences.addAll(outcome.divergences);
                peak = Math.max(peak, outcome.peak);
                seenThisRun.add(openPartition);
                if (outcome.divergences.isEmpty()) {
                    completedThisRun.add(openPartition);
                    completedRead += outcome.rowsRead;
                    completedWritten += outcome.rowsWritten;
                    persistCheckpoint(config, completedBefore, completedThisRun,
                            openPartition, completedRead, completedWritten);
                }
                buffer = new ArrayList<Map<String, Object>>();
                openPartition = null;
                openPk = null;
            }

            if (openPartition == null) {
                openPartition = part;
                openPk = extractPrimaryKey(row);
            }
            buffer.add(row);
            peak = Math.max(peak, buffer.size());
        }

        if (openPartition != null) {
            PartitionOutcome outcome = flushPartition(buffer, openPk, config, limiter);
            rowsRead += outcome.rowsRead;
            rowsWritten += outcome.rowsWritten;
            partitionReads += outcome.partitionReads;
            divergences.addAll(outcome.divergences);
            peak = Math.max(peak, outcome.peak);
            seenThisRun.add(openPartition);
            if (outcome.divergences.isEmpty()) {
                completedThisRun.add(openPartition);
                completedRead += outcome.rowsRead;
                completedWritten += outcome.rowsWritten;
                persistCheckpoint(config, completedBefore, completedThisRun,
                        openPartition, completedRead, completedWritten);
            }
        }

        if (!seenRow && existing == null) {
            return emptyResult(config);
        }
        return new BackfillResult(rowsRead, rowsWritten, divergences, false, peak, partitionReads);
    }

    private PartitionOutcome flushPartition(List<Map<String, Object>> buffer,
                                            Map<String, Object> pk,
                                            BackfillConfig config,
                                            WriteRateLimiter limiter) {
        MappedRowSetDiffer.requireUniqueIdentities(mapping, buffer);
        int chunk = config.maxBufferedRows();
        for (int i = 0; i < buffer.size(); i += chunk) {
            int to = Math.min(i + chunk, buffer.size());
            List<Map<String, Object>> slice = new ArrayList<Map<String, Object>>(buffer.subList(i, to));
            if (limiter != null) {
                limiter.acquire(slice.size());
            }
            writer.batchUpsert(slice);
        }
        List<Map<String, Object>> dest = readBack(pk);
        List<String> divergences;
        try {
            divergences = MappedRowSetDiffer.compare(mapping, buffer, dest);
        } catch (IllegalArgumentException ex) {
            divergences = new ArrayList<String>();
            divergences.add(ex.getMessage());
        }
        int written = 0;
        for (int i = 0; i < buffer.size(); i++) {
            if (hasIdenticalCounterpart(buffer.get(i), dest)) {
                written++;
            }
        }
        return new PartitionOutcome(buffer.size(), written, 1, buffer.size(), divergences);
    }

    private BackfillCheckpoint loadCheckpoint(BackfillConfig config) {
        if (config.checkpointStore() == null) {
            return null;
        }
        if (config.snapshotId() == null || config.snapshotId().isEmpty()) {
            throw new IllegalArgumentException(
                    "snapshotId is required when a checkpoint store is configured");
        }
        BackfillCheckpoint existing = config.checkpointStore().load();
        if (existing != null && !config.snapshotId().equals(existing.snapshotId())) {
            throw new IllegalStateException(
                    "source snapshot changed: checkpoint is for '" + existing.snapshotId()
                            + "' but this run is '" + config.snapshotId()
                            + "'. Refusing to resume against a different source.");
        }
        return existing;
    }

    private void persistCheckpoint(BackfillConfig config,
                                   Set<String> completedBefore,
                                   Set<String> completedThisRun,
                                   String watermark,
                                   int rowsRead,
                                   int rowsWritten) {
        if (config.checkpointStore() == null) {
            return;
        }
        Set<String> all = new LinkedHashSet<String>(completedBefore);
        all.addAll(completedThisRun);
        config.checkpointStore().save(new BackfillCheckpoint(
                config.snapshotId(), watermark, all, rowsRead, rowsWritten));
    }

    private BackfillResult emptyResult(BackfillConfig config) {
        if (config.allowEmptySource()) {
            return new BackfillResult(0, 0, Collections.<String>emptyList(), true, 0, 0);
        }
        return new BackfillResult(0, 0, Collections.<String>emptyList(), false, 0, 0);
    }

    private boolean hasIdenticalCounterpart(Map<String, Object> source,
                                            List<Map<String, Object>> dest) {
        String id = MappedRowSetDiffer.identityOf(mapping, source);
        for (Map<String, Object> candidate : dest) {
            if (id.equals(MappedRowSetDiffer.identityOf(mapping, candidate))
                    && MappedRowSetDiffer.rowsEqual(mapping, source, candidate)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> extractPrimaryKey(Map<String, Object> row) {
        Map<String, Object> pk = new LinkedHashMap<String, Object>();
        for (AttributeMapping a : mapping.primaryKeyAttributes()) {
            Object v = row.get(a.javaName());
            if (v == null) {
                throw new IllegalArgumentException(
                        "missing primary-key value for " + mapping.className() + "." + a.javaName()
                                + ": a partition-key component is never optional");
            }
            pk.put(a.javaName(), v);
        }
        return pk;
    }

    private void requireFullPrimaryKey(Map<String, Object> row) {
        extractPrimaryKey(row);
    }

    private List<String> boundaryNames() {
        List<String> out = new ArrayList<String>();
        if (mapping.temporal().hasBusinessDate()) {
            out.add(TemporalAttributeNames.businessFromJavaName(mapping));
            out.add(TemporalAttributeNames.businessToJavaName(mapping));
        }
        if (mapping.temporal().hasProcessingDate()) {
            out.add(TemporalAttributeNames.processingFromJavaName(mapping));
            out.add(TemporalAttributeNames.processingToJavaName(mapping));
        }
        return out;
    }

    /**
     * A dated row without its boundaries cannot be addressed, and a migration that skipped it would
     * lose history silently. Fail before writing anything.
     */
    private void requireBoundaries(Map<String, Object> row) {
        for (String name : boundaryNames()) {
            Object v = row.get(name);
            if (!(v instanceof Timestamp)) {
                StringBuilder id = new StringBuilder();
                for (AttributeMapping a : mapping.primaryKeyAttributes()) {
                    id.append(a.javaName()).append('=').append(row.get(a.javaName())).append(' ');
                }
                throw new IllegalArgumentException(
                        "source row [" + id.toString().trim() + "] is missing temporal boundary '"
                                + name + "'. Refusing the whole backfill rather than copying a row "
                                + "that cannot be addressed or read back.");
            }
        }
    }

    private static final class PartitionOutcome {
        private final int rowsRead;
        private final int rowsWritten;
        private final int partitionReads;
        private final int peak;
        private final List<String> divergences;

        private PartitionOutcome(int rowsRead, int rowsWritten, int partitionReads, int peak,
                                 List<String> divergences) {
            this.rowsRead = rowsRead;
            this.rowsWritten = rowsWritten;
            this.partitionReads = partitionReads;
            this.peak = peak;
            this.divergences = divergences;
        }
    }
}
