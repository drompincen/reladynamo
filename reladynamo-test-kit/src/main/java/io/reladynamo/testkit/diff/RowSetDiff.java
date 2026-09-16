package io.reladynamo.testkit.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The verdict of one differential comparison. */
public final class RowSetDiff {

    private final List<String> divergences;
    private final int leftSize;
    private final int rightSize;

    RowSetDiff(List<String> divergences, int leftSize, int rightSize) {
        this.divergences = Collections.unmodifiableList(new ArrayList<>(divergences));
        this.leftSize = leftSize;
        this.rightSize = rightSize;
    }

    public boolean isIdentical() {
        return divergences.isEmpty();
    }

    public List<String> divergences() {
        return divergences;
    }

    /** Human-readable verdict. Kept explicit for the empty case so a vacuous pass is visible. */
    public String describe() {
        if (divergences.isEmpty()) {
            if (leftSize == 0 && rightSize == 0) {
                return "identical: both row sets are EMPTY (no rows compared — "
                        + "check the query actually selected something)";
            }
            return "identical: " + leftSize + " row(s) matched on every attribute";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(divergences.size()).append(" divergence(s) between reference and adapter:\n");
        for (String d : divergences) {
            sb.append("  - ").append(d).append('\n');
        }
        return sb.toString();
    }
}
