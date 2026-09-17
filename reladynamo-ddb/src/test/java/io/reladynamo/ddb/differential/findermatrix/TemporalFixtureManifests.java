package io.reladynamo.ddb.differential.findermatrix;

import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * FINDER-MATRIX.md §5 temporal bundles B, B', A. Dates are UTC millisecond Timestamps.
 * {@code I} is the generated axis infinity, not a guessed calendar date.
 */
final class TemporalFixtureManifests {

    static final Timestamp B0 = utc(2026, 1, 1, 0, 0, 0, 0);
    static final Timestamp B1 = utc(2026, 6, 1, 0, 0, 0, 0);
    static final Timestamp B2 = utc(2026, 9, 1, 0, 0, 0, 0);
    static final Timestamp P0 = utc(2026, 3, 1, 9, 0, 0, 0);
    static final Timestamp P1 = utc(2026, 3, 2, 9, 0, 0, 0);

    private TemporalFixtureManifests() {
    }

    static Timestamp I() {
        return DiffBalanceFinder.businessDate().getInfinityDate();
    }

    static Timestamp plus1(Timestamp t) {
        return new Timestamp(t.getTime() + 1L);
    }

    static Timestamp minus1(Timestamp t) {
        return new Timestamp(t.getTime() - 1L);
    }

    static Timestamp utc(int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(ms * 1_000_000);
        return t;
    }

    static List<BalanceRow> B() {
        return rectangles(201);
    }

    static List<BalanceRow> Bprime() {
        return rectangles(202);
    }

    static List<BalanceRow> BplusBprime() {
        List<BalanceRow> rows = new ArrayList<BalanceRow>();
        rows.addAll(B());
        rows.addAll(Bprime());
        return rows;
    }

    static List<AuditRow> A() {
        Timestamp inf = I();
        List<AuditRow> rows = new ArrayList<AuditRow>();
        rows.add(new AuditRow(301, 10.0d, "old", null, P0, P1));
        rows.add(new AuditRow(301, 55.0d, "new", "present", P1, inf));
        return rows;
    }

    private static List<BalanceRow> rectangles(int balanceId) {
        Timestamp inf = I();
        List<BalanceRow> rows = new ArrayList<BalanceRow>();
        rows.add(new BalanceRow(balanceId, 10.0d, "old", null, B0, B2, P0, P1));
        rows.add(new BalanceRow(balanceId, 10.0d, "prefix", null, B0, B1, P1, inf));
        rows.add(new BalanceRow(balanceId, 55.0d, "corrected", null, B1, B2, P1, inf));
        rows.add(new BalanceRow(balanceId, 80.0d, "future", null, B2, inf, P0, inf));
        return rows;
    }

    static final class BalanceRow {
        final int balanceId;
        final double quantity;
        final String label;
        final String note;
        final Timestamp businessFrom;
        final Timestamp businessTo;
        final Timestamp processingFrom;
        final Timestamp processingTo;

        BalanceRow(int balanceId, double quantity, String label, String note,
                   Timestamp businessFrom, Timestamp businessTo,
                   Timestamp processingFrom, Timestamp processingTo) {
            this.balanceId = balanceId;
            this.quantity = quantity;
            this.label = label;
            this.note = note;
            this.businessFrom = (Timestamp) businessFrom.clone();
            this.businessTo = (Timestamp) businessTo.clone();
            this.processingFrom = (Timestamp) processingFrom.clone();
            this.processingTo = (Timestamp) processingTo.clone();
        }

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("balanceId", Integer.valueOf(balanceId));
            row.put("quantity", Double.valueOf(quantity));
            row.put("label", label);
            row.put("note", note);
            row.put("businessDateFrom", (Timestamp) businessFrom.clone());
            row.put("businessDateTo", (Timestamp) businessTo.clone());
            row.put("processingDateFrom", (Timestamp) processingFrom.clone());
            row.put("processingDateTo", (Timestamp) processingTo.clone());
            return row;
        }
    }

    static final class AuditRow {
        final int auditId;
        final double quantity;
        final String label;
        final String note;
        final Timestamp processingFrom;
        final Timestamp processingTo;

        AuditRow(int auditId, double quantity, String label, String note,
                 Timestamp processingFrom, Timestamp processingTo) {
            this.auditId = auditId;
            this.quantity = quantity;
            this.label = label;
            this.note = note;
            this.processingFrom = (Timestamp) processingFrom.clone();
            this.processingTo = (Timestamp) processingTo.clone();
        }

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("auditId", Integer.valueOf(auditId));
            row.put("quantity", Double.valueOf(quantity));
            row.put("label", label);
            row.put("note", note);
            row.put("processingDateFrom", (Timestamp) processingFrom.clone());
            row.put("processingDateTo", (Timestamp) processingTo.clone());
            return row;
        }
    }
}
