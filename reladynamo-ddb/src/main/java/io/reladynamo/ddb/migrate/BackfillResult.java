package io.reladynamo.ddb.migrate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What a backfill actually did — not what it was asked to do. */
public final class BackfillResult {

    private final int rowsRead;
    private final int rowsWritten;
    private final List<String> divergences;
    private final boolean validEmptyTable;
    private final int peakBufferedRows;
    private final int partitionReads;

    BackfillResult(int rowsRead, int rowsWritten, List<String> divergences) {
        this(rowsRead, rowsWritten, divergences, false, 0, 0);
    }

    BackfillResult(int rowsRead, int rowsWritten, List<String> divergences,
                   boolean validEmptyTable, int peakBufferedRows, int partitionReads) {
        this.rowsRead = rowsRead;
        this.rowsWritten = rowsWritten;
        this.divergences = Collections.unmodifiableList(new ArrayList<String>(divergences));
        this.validEmptyTable = validEmptyTable;
        this.peakBufferedRows = peakBufferedRows;
        this.partitionReads = partitionReads;
    }

    public int rowsRead() {
        return rowsRead;
    }

    public int rowsWritten() {
        return rowsWritten;
    }

    public List<String> divergences() {
        return divergences;
    }

    /**
     * True when the source was empty <em>and</em> the caller declared that as a legitimate
     * empty table, not a misconfigured query.
     */
    public boolean validEmptyTable() {
        return validEmptyTable;
    }

    /**
     * Peak number of source rows held together during the run. The bound is one logical
     * partition (see {@link BackfillConfig}).
     */
    public int peakBufferedRows() {
        return peakBufferedRows;
    }

    /**
     * Destination partition reads issued during this result's construction. Linear in keys,
     * not in versions: one read per logical key, not one per source row.
     */
    public int partitionReads() {
        return partitionReads;
    }

    /**
     * True when rows were actually copied and read back identical, or when the caller
     * declared a valid empty table and the source was empty.
     *
     * <p>An undeclared empty source is deliberately <b>not</b> verified. "Migrated 0 rows,
     * verified" is the most dangerous green a migration can print: it is indistinguishable
     * from a misconfigured source.
     */
    public boolean verified() {
        if (validEmptyTable) {
            return rowsRead == 0 && rowsWritten == 0 && divergences.isEmpty();
        }
        return rowsRead > 0 && rowsWritten == rowsRead && divergences.isEmpty();
    }

    public String summary() {
        if (validEmptyTable) {
            return "empty table: nothing to copy (declared valid-empty). 0 rows written.";
        }
        if (rowsRead == 0) {
            return "source was EMPTY — nothing copied, and nothing verified. Check the source query "
                    + "before treating this as success.";
        }
        if (!divergences.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("read ").append(rowsRead).append(", wrote ").append(rowsWritten)
                    .append(", ").append(divergences.size()).append(" divergence(s):\n");
            for (String d : divergences) {
                sb.append("  - ").append(d).append('\n');
            }
            return sb.toString();
        }
        return "read " + rowsRead + ", wrote " + rowsWritten + ", verified identical on read-back";
    }
}
