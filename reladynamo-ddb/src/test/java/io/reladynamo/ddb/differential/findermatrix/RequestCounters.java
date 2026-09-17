package io.reladynamo.ddb.differential.findermatrix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Split Query / GetItem / ExecuteStatement / BatchGetItem / Scan counters. Increments on
 * entry so an error cannot erase an attempted read. PartiQL is counted separately: counting
 * only Query/GetItem would misclassify collapsed fan-out as a cache hit.
 */
final class RequestCounters {

    final AtomicInteger query = new AtomicInteger();
    final AtomicInteger getItem = new AtomicInteger();
    final AtomicInteger executeStatement = new AtomicInteger();
    final AtomicInteger batchGetItem = new AtomicInteger();
    final AtomicInteger scan = new AtomicInteger();
    private final List<String> records = Collections.synchronizedList(new ArrayList<String>());

    void reset() {
        query.set(0);
        getItem.set(0);
        executeStatement.set(0);
        batchGetItem.set(0);
        scan.set(0);
        records.clear();
    }

    int dataReads() {
        return query.get() + getItem.get() + executeStatement.get() + batchGetItem.get() + scan.get();
    }

    void record(String family, String detail) {
        records.add(family + " " + detail);
    }

    List<String> records() {
        return new ArrayList<String>(records);
    }

    String describe() {
        return "query=" + query.get()
                + " getItem=" + getItem.get()
                + " executeStatement=" + executeStatement.get()
                + " batchGetItem=" + batchGetItem.get()
                + " scan=" + scan.get();
    }

    RequestCounters copy() {
        RequestCounters out = new RequestCounters();
        out.query.set(query.get());
        out.getItem.set(getItem.get());
        out.executeStatement.set(executeStatement.get());
        out.batchGetItem.set(batchGetItem.get());
        out.scan.set(scan.get());
        out.records.addAll(records);
        return out;
    }
}
