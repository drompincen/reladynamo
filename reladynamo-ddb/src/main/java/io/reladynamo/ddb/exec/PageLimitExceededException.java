package io.reladynamo.ddb.exec;

import com.gs.fw.common.mithra.MithraDatabaseException;

/**
 * Raised when a query needs more pages than its plan allows.
 *
 * <p>The alternative — stopping quietly and returning what was read so far — is worse than failing.
 * A caller cannot distinguish a complete result from a clipped one, so a truncated read looks exactly
 * like a smaller table. This is the same reasoning that makes the planner refuse a Scan rather than
 * run one.
 *
 * <p>Extends {@link MithraDatabaseException} so callers see Reladomo's exception contract.
 */
public class PageLimitExceededException extends MithraDatabaseException {

    private final int pages;
    private final int limit;

    public PageLimitExceededException(String table, int pages, int limit, int rowsSoFar) {
        super("RELADYNAMO-PLAN-006: query on '" + table + "' exceeded its page limit: read " + pages
                + " page(s) with a limit of " + limit + " (maxPages=" + limit + ") and more remained ("
                + rowsSoFar + " row(s) so far). Returning a partial result would be indistinguishable "
                + "from a complete one, so this fails instead. Raise PlannerConfig.maxPages for a "
                + "legitimately large read, or narrow the query.");
        this.pages = pages;
        this.limit = limit;
    }

    public int pages() {
        return pages;
    }

    public int limit() {
        return limit;
    }
}
