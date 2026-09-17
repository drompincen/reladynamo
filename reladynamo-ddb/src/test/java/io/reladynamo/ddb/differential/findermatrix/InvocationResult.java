package io.reladynamo.ddb.differential.findermatrix;

import io.reladynamo.ddb.exec.ExecutionExplain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable outcome of one backend invocation. Reladomo objects are not retained. */
final class InvocationResult {

    final List<TypedSnapshot> rows;
    final Integer count;
    final RequestCounters counters;
    final ExecutionExplain explain;
    final int readerEntries;
    final int sqlSelects;

    InvocationResult(List<TypedSnapshot> rows, Integer count, RequestCounters counters,
                     ExecutionExplain explain, int readerEntries, int sqlSelects) {
        this.rows = Collections.unmodifiableList(new ArrayList<TypedSnapshot>(rows));
        this.count = count;
        this.counters = counters;
        this.explain = explain;
        this.readerEntries = readerEntries;
        this.sqlSelects = sqlSelects;
    }

    static InvocationResult rows(List<TypedSnapshot> rows, RequestCounters counters,
                                 ExecutionExplain explain, int readerEntries, int sqlSelects) {
        return new InvocationResult(rows, Integer.valueOf(rows.size()), counters, explain,
                readerEntries, sqlSelects);
    }

    static InvocationResult countOnly(int count, RequestCounters counters, ExecutionExplain explain,
                                      int readerEntries, int sqlSelects) {
        return new InvocationResult(Collections.<TypedSnapshot>emptyList(), Integer.valueOf(count),
                counters, explain, readerEntries, sqlSelects);
    }
}
