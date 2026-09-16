package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraTransactionException;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.ddb.differential.DifferentialSupport.Store;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edge cases that actually break bitemporal code: infinity, zero-length segments, out-of-order
 * business dates, same-millisecond writes, terminate-then-reinsert, and full history reconstruction.
 *
 * <p>Processing time is pinned so the row sets are deterministic. Reladomo 18.1.0 clamps processing
 * timestamps to 10ms ({@code getProcessingStartTime()/10*10} in
 * {@code GenericBiTemporalDirector.createProcessingTimestamp}); fixtures sit on that grid.
 */
class BitemporalEdgeCaseDifferentialTest {

    private static final long P0 = DifferentialSupport.utc(2020, 5, 1, 9, 0, 0, 0).getTime();
    private static final long P1 = DifferentialSupport.utc(2020, 5, 1, 9, 0, 1, 0).getTime();
    private static final long P2 = DifferentialSupport.utc(2020, 5, 1, 9, 0, 2, 0).getTime();
    private static final long P3 = DifferentialSupport.utc(2020, 5, 1, 9, 0, 3, 0).getTime();
    /** Same processing millisecond, already on Reladomo's 10ms clamp grid. */
    private static final long SAME_MS = DifferentialSupport.utc(2020, 5, 1, 9, 30, 0, 0).getTime();
    /** 5ms later — still clamps to SAME_MS because Reladomo floors to 10ms. */
    private static final long SAME_MS_PLUS_5 = SAME_MS + 5L;
    /** Next 10ms bucket: a legal processing-time successor of SAME_MS. */
    private static final long NEXT_10MS = SAME_MS + 10L;

    private static LocalDynamoDb ddb;
    private static Store store;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        store = DifferentialSupport.openStore(ddb, "/reladomo/models/DiffBalance.xml");
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void infinity_sentinel_is_preserved_exactly_on_open_rows() {
        int id = 300;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertOpening(id, 10.0, "open");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        Timestamp inf = DifferentialSupport.infinity();
        boolean sawOpen = false;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (inf.equals(row.get("businessDateTo")) && inf.equals(row.get("processingDateTo"))) {
                sawOpen = true;
            }
        }
        assertThat(sawOpen)
                .as("insert must leave a row open at the configured infinity sentinel:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isTrue();
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void insertUntil_of_a_one_millisecond_window_round_trips() {
        // Smallest legal half-open business window: [from, from+1ms). as-of `from` matches.
        int id = 301;
        Timestamp from = biz(2026, 6, 1);
        Timestamp until = new Timestamp(from.getTime() + 1L);
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance b = new DiffBalance(from);
            b.setBalanceId(id);
            b.setQuantity(1.0);
            b.setLabel("one-ms");
            b.insertUntil(until);
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        assertThat(rows).isNotEmpty();
        boolean sawWindow = false;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Timestamp bf = (Timestamp) row.get("businessDateFrom");
            Timestamp bt = (Timestamp) row.get("businessDateTo");
            if (from.equals(bf) && until.equals(bt)) {
                sawWindow = true;
            }
        }
        assertThat(sawWindow)
                .as("insertUntil of a 1ms window must persist [from, from+1ms):%n%s",
                        DifferentialSupport.describeRows(rows))
                .isTrue();
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void insertUntil_of_a_zero_length_window_is_persisted_as_from_equals_to() {
        // insertUntil does NOT call checkDatesAreWithinRange. exclusiveUntil == businessDate
        // writes businessDateTo = from and insert() stores the degenerate [from, from) rectangle.
        // Contrast insertForRecovery, which rejects the same shape (finding 9).
        int id = 302;
        Timestamp from = biz(2026, 6, 1);
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance b = new DiffBalance(from);
            b.setBalanceId(id);
            b.setQuantity(1.0);
            b.setLabel("zero-until");
            b.insertUntil(from);
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        assertThat(rows)
                .as("insertUntil(from==asOf) must persist a row; equalsEdgePoint sees it even though "
                        + "no as-of query can:%n%s", DifferentialSupport.describeRows(rows))
                .isNotEmpty();
        boolean sawZero = false;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Timestamp bf = (Timestamp) row.get("businessDateFrom");
            Timestamp bt = (Timestamp) row.get("businessDateTo");
            if (bf != null && bf.equals(bt)) {
                sawZero = true;
            }
        }
        assertThat(sawZero)
                .as("insertUntil zero-length window must store from==thru:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isTrue();
        assertThat(asOf(rows, from, DifferentialSupport.infinity()))
                .as("a zero-length rectangle matches no as-of date, including its own from")
                .isNull();
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void insertForRecovery_rejects_a_zero_length_business_segment() {
        // Reladomo 18.1.0 GenericBiTemporalDirector.insertForRecovery → checkDatesAreWithinRange.
        // AsOfAttribute.dataMatches is half-open: from <= asOf < to. from==to matches nothing,
        // including the object's own as-of date, so recovery of a zero-length business segment
        // is illegal. Not an adapter bug — the original test premise was wrong.
        int id = 303;
        Timestamp from = biz(2026, 6, 1);
        assertThatThrownBy(() -> DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance recovered = new DiffBalance(from, proc(P0));
            recovered.setBalanceId(id);
            recovered.setQuantity(1.0);
            recovered.setLabel("zero-recovery");
            recovered.setBusinessDateFrom(from);
            recovered.setBusinessDateTo(from);
            recovered.setProcessingDateFrom(proc(P0));
            recovered.setProcessingDateTo(DifferentialSupport.infinity());
            recovered.insertForRecovery();
            return null;
        }))
                .isInstanceOf(MithraTransactionException.class)
                .hasMessageContaining("business date must be valid for to and from business dates");
        assertThat(allVersions(id))
                .as("rejected recovery must leave no H2 row")
                .isEmpty();
    }

    @Test
    void insertForRecovery_rejects_a_zero_length_processing_segment() {
        int id = 304;
        Timestamp from = biz(2026, 6, 1);
        Timestamp to = biz(2026, 9, 1);
        assertThatThrownBy(() -> DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance recovered = new DiffBalance(from, proc(P0));
            recovered.setBalanceId(id);
            recovered.setQuantity(1.0);
            recovered.setLabel("zero-proc");
            recovered.setBusinessDateFrom(from);
            recovered.setBusinessDateTo(to);
            recovered.setProcessingDateFrom(proc(P0));
            recovered.setProcessingDateTo(proc(P0));
            recovered.insertForRecovery();
            return null;
        }))
                .isInstanceOf(MithraTransactionException.class)
                .hasMessageContaining("processing date must be valid for to and from processing dates");
        assertThat(allVersions(id)).isEmpty();
    }

    @Test
    void insertForRecovery_of_a_closed_non_zero_segment_round_trips() {
        int id = 305;
        Timestamp from = biz(2026, 1, 1);
        Timestamp to = biz(2026, 6, 1);
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance recovered = new DiffBalance(from, proc(P0));
            recovered.setBalanceId(id);
            recovered.setQuantity(7.0);
            recovered.setLabel("closed-recovery");
            recovered.setBusinessDateFrom(from);
            recovered.setBusinessDateTo(to);
            recovered.setProcessingDateFrom(proc(P0));
            recovered.setProcessingDateTo(DifferentialSupport.infinity());
            recovered.insertForRecovery();
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        Map<String, Object> current = asOf(rows, from, DifferentialSupport.infinity());
        assertThat(current)
                .as("closed recovery [Jan, June) must be visible as-of Jan:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(current.get("quantity")).isEqualTo(Double.valueOf(7.0));
        assertThat(asOf(rows, to, DifferentialSupport.infinity()))
                .as("exclusive until must not match")
                .isNull();
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void out_of_order_business_date_correction_round_trips() {
        int id = 306;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertOpeningAt(id, biz(2026, 6, 1), 10.0, "later");
            return null;
        });
        // A finder as-of 2026-01-01 returns null: the existing segment starts in June.
        // insertUntil with exclusiveUntil equal to the later FROM makes the ranges abut.
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance earlier = new DiffBalance(biz(2026, 1, 1));
            earlier.setBalanceId(id);
            earlier.setQuantity(3.0);
            earlier.setLabel("earlier-correction");
            earlier.insertUntil(biz(2026, 6, 1));
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        Map<String, Object> jan = asOf(rows, biz(2026, 1, 1), DifferentialSupport.infinity());
        Map<String, Object> jun = asOf(rows, biz(2026, 6, 1), DifferentialSupport.infinity());
        assertThat(jan)
                .as("current as-of Jan must be the earlier fill:%n%s", DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(jan.get("label")).isEqualTo("earlier-correction");
        assertThat(jan.get("quantity")).isEqualTo(Double.valueOf(3.0));
        assertThat(jun)
                .as("current as-of June must still be the original later segment:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(jun.get("label")).isEqualTo("later");
        assertThat(jun.get("quantity")).isEqualTo(Double.valueOf(10.0));
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void late_arriving_fact_in_the_middle_of_the_timeline_round_trips() {
        // Processing order: Jan insert, then June update, then a March correction — business
        // time is out of order relative to processing time.
        //
        // Unbounded setQuantity as-of March is NOT a hole-fill. GenericBiTemporalDirector.update
        // ranges [asOf, infinity), so the March write also inactivates the later June segment.
        // Current as-of June is therefore 15, not 20. The June value survives only historically
        // (as-of processing P1). Contrast late_arriving_fact_dated_until_the_next_segment.
        int id = 307;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertOpeningAt(id, biz(2026, 1, 1), 10.0, "jan");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantity(20.0);
            found.setLabel("june");
            return null;
        });
        DifferentialSupport.inTransaction(P2, tx -> {
            DiffBalance found = findOn(id, biz(2026, 3, 1));
            found.setQuantity(15.0);
            found.setLabel("march-late");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        Map<String, Object> marchNow = asOf(rows, biz(2026, 3, 1), DifferentialSupport.infinity());
        Map<String, Object> juneNow = asOf(rows, biz(2026, 6, 1), DifferentialSupport.infinity());
        Map<String, Object> marchAtP1 = asOf(rows, biz(2026, 3, 1), proc(P1));
        Map<String, Object> juneAtP1 = asOf(rows, biz(2026, 6, 1), proc(P1));
        assertThat(marchNow)
                .as("current belief at March is the late fact:%n%s", DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(marchNow.get("quantity")).isEqualTo(Double.valueOf(15.0));
        assertThat(juneNow)
                .as("unbounded setQuantity as-of March applies through infinity, so current June "
                        + "is also the late fact:%n%s", DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(juneNow.get("quantity")).isEqualTo(Double.valueOf(15.0));
        assertThat(juneNow.get("label")).isEqualTo("march-late");
        assertThat(marchAtP1)
                .as("as of P1 (from of the June version), March still showed the January value:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(marchAtP1.get("quantity")).isEqualTo(Double.valueOf(10.0));
        assertThat(juneAtP1)
                .as("as of P1, June still showed the June update:%n%s", DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(juneAtP1.get("quantity")).isEqualTo(Double.valueOf(20.0));
        assertDistinctSortKeys(rows);
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void late_arriving_fact_dated_until_the_next_segment_preserves_later_values() {
        // Same processing order as the unbounded case, but setQuantityUntil(June) only rewrites
        // [March, June). The later [June, inf) segment is outside the Until range and survives
        // as current belief. That is the hole-fill the unbounded test was written as.
        int id = 315;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertOpeningAt(id, biz(2026, 1, 1), 10.0, "jan");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantity(20.0);
            found.setLabel("june");
            return null;
        });
        DifferentialSupport.inTransaction(P2, tx -> {
            DiffBalance found = findOn(id, biz(2026, 3, 1));
            found.setQuantityUntil(15.0, biz(2026, 6, 1));
            found.setLabelUntil("march-late", biz(2026, 6, 1));
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        Map<String, Object> marchNow = asOf(rows, biz(2026, 3, 1), DifferentialSupport.infinity());
        Map<String, Object> juneNow = asOf(rows, biz(2026, 6, 1), DifferentialSupport.infinity());
        assertThat(marchNow)
                .as("current belief at March is the dated-until late fact:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(marchNow.get("quantity")).isEqualTo(Double.valueOf(15.0));
        assertThat(marchNow.get("label")).isEqualTo("march-late");
        assertThat(juneNow)
                .as("setQuantityUntil(June) must leave the later June segment current:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(juneNow.get("quantity")).isEqualTo(Double.valueOf(20.0));
        assertThat(juneNow.get("label")).isEqualTo("june");
        assertDistinctSortKeys(rows);
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void same_millisecond_update_at_a_later_business_date_splits_without_a_new_processing_version() {
        // Insert at Jan, then setQuantity as-of June in a second tx pinned to the same processing
        // millisecond. Reladomo splits the business range. Both rectangles share processingFrom
        // (the 10ms-clamped tx start) and stay open on processingTo — there is no inactivated
        // prior belief, because inactivateObject sees processingFrom == txStartTime and deletes
        // the original [Jan, inf) instead of closing it. Distinct businessDateFrom ⇒ distinct
        // v1 sort keys, so DynamoDB does not collide.
        int id = 308;
        DifferentialSupport.inTransaction(SAME_MS, tx -> {
            insertOpening(id, 10.0, "first");
            return null;
        });
        DifferentialSupport.inTransaction(SAME_MS, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantity(11.0);
            found.setLabel("second-same-ms");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        assertThat(rows)
                .as("same-ms split must leave two current rectangles:%n%s",
                        DifferentialSupport.describeRows(rows))
                .hasSize(2);
        Map<String, Object> jan = asOf(rows, biz(2026, 1, 1), DifferentialSupport.infinity());
        Map<String, Object> jun = asOf(rows, biz(2026, 6, 1), DifferentialSupport.infinity());
        assertThat(jan).isNotNull();
        assertThat(jan.get("quantity")).isEqualTo(Double.valueOf(10.0));
        assertThat(jan.get("label")).isEqualTo("first");
        assertThat(jun).isNotNull();
        assertThat(jun.get("quantity")).isEqualTo(Double.valueOf(11.0));
        assertThat(jun.get("label")).isEqualTo("second-same-ms");
        assertThat(jan.get("processingDateFrom")).isEqualTo(jun.get("processingDateFrom"));
        assertThat(jan.get("processingDateTo")).isEqualTo(DifferentialSupport.infinity());
        assertDistinctSortKeys(rows);
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void same_millisecond_update_at_the_from_date_deletes_the_prior_belief() {
        // Same processing millisecond, as-of the segment's own FROM. inactivateObject hits
        // "has changed too fast" and physically deletes rather than closing processingTo.
        int id = 314;
        DifferentialSupport.inTransaction(SAME_MS, tx -> {
            insertOpening(id, 10.0, "first");
            return null;
        });
        DifferentialSupport.inTransaction(SAME_MS, tx -> {
            DiffBalance found = findOn(id, biz(2026, 1, 1));
            found.setQuantity(11.0);
            found.setLabel("replaced");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        assertThat(rows)
                .as("same-ms update at FROM must leave exactly one physical row:%n%s",
                        DifferentialSupport.describeRows(rows))
                .hasSize(1);
        assertThat(rows.get(0).get("quantity")).isEqualTo(Double.valueOf(11.0));
        assertThat(rows.get(0).get("label")).isEqualTo("replaced");
        assertThat(rows.get(0).get("processingDateFrom")).isEqualTo(proc(SAME_MS));
        assertThat(rows.get(0).get("processingDateTo")).isEqualTo(DifferentialSupport.infinity());
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void writes_five_milliseconds_apart_also_collapse_because_of_the_ten_ms_clamp() {
        int id = 309;
        DifferentialSupport.inTransaction(SAME_MS, tx -> {
            insertOpening(id, 10.0, "first");
            return null;
        });
        DifferentialSupport.inTransaction(SAME_MS_PLUS_5, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantity(12.0);
            found.setLabel("clamped");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        assertThat(rows.size())
                .as("5ms later still floors to SAME_MS and splits like a same-ms write:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isEqualTo(2);
        assertThat(((Timestamp) rows.get(0).get("processingDateFrom")).getTime()).isEqualTo(SAME_MS);
        assertThat(((Timestamp) rows.get(1).get("processingDateFrom")).getTime()).isEqualTo(SAME_MS);
        Map<String, Object> jun = asOf(rows, biz(2026, 6, 1), DifferentialSupport.infinity());
        assertThat(jun).isNotNull();
        assertThat(jun.get("quantity")).isEqualTo(Double.valueOf(12.0));
        assertDistinctSortKeys(rows);
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void writes_ten_milliseconds_apart_inactivate_normally() {
        int id = 310;
        DifferentialSupport.inTransaction(SAME_MS, tx -> {
            insertOpening(id, 10.0, "first");
            return null;
        });
        DifferentialSupport.inTransaction(NEXT_10MS, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantity(13.0);
            found.setLabel("next-bucket");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        assertThat(rows.size())
                .as("10ms later must inactivate rather than delete:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isGreaterThan(1);
        Map<String, Object> before = asOf(rows, biz(2026, 6, 1), proc(SAME_MS));
        Map<String, Object> after = asOf(rows, biz(2026, 6, 1), proc(NEXT_10MS));
        assertThat(before)
                .as("as-of the first processing FROM, June still shows 10:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(before.get("quantity")).isEqualTo(Double.valueOf(10.0));
        assertThat(after)
                .as("as-of the next 10ms bucket, June shows the update:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(after.get("quantity")).isEqualTo(Double.valueOf(13.0));
        assertDistinctSortKeys(rows);
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void chained_terminate_then_reinsert_round_trips() {
        int id = 311;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertOpening(id, 10.0, "first-life");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 1, 1)).terminate();
            return null;
        });
        DifferentialSupport.inTransaction(P2, tx -> {
            insertOpeningAt(id, biz(2026, 9, 1), 20.0, "second-life");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        assertThat(asOf(rows, biz(2026, 6, 1), DifferentialSupport.infinity()))
                .as("after full terminate, current as-of June must be empty (second life starts in Sep):%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNull();
        Map<String, Object> second = asOf(rows, biz(2026, 9, 1), DifferentialSupport.infinity());
        assertThat(second).isNotNull();
        assertThat(second.get("label")).isEqualTo("second-life");
        assertThat(second.get("quantity")).isEqualTo(Double.valueOf(20.0));
        Map<String, Object> firstLife = asOf(rows, biz(2026, 6, 1), proc(P0));
        assertThat(firstLife)
                .as("as-of P0 (first life's processing FROM), June still shows first-life:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(firstLife.get("label")).isEqualTo("first-life");
        assertDistinctSortKeys(rows);
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void terminate_from_the_middle_then_reinsert_later_round_trips() {
        int id = 312;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertOpening(id, 10.0, "first-life");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 6, 1)).terminate();
            return null;
        });
        DifferentialSupport.inTransaction(P2, tx -> {
            insertOpeningAt(id, biz(2026, 9, 1), 20.0, "second-life");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        Map<String, Object> jan = asOf(rows, biz(2026, 1, 1), DifferentialSupport.infinity());
        assertThat(jan)
                .as("terminate as-of June keeps the January–June head:%n%s",
                        DifferentialSupport.describeRows(rows))
                .isNotNull();
        assertThat(jan.get("label")).isEqualTo("first-life");
        assertThat(asOf(rows, biz(2026, 6, 1), DifferentialSupport.infinity()))
                .as("June onwards is terminated until the September reinsert")
                .isNull();
        assertThat(asOf(rows, biz(2026, 9, 1), DifferentialSupport.infinity()).get("label"))
                .isEqualTo("second-life");
        assertDistinctSortKeys(rows);
        store.assertAgrees("balanceId", id, rows);
    }

    @Test
    void full_history_reconstruction_matches_processing_date_order() {
        int id = 313;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertOpening(id, 10.0, "v0");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantity(20.0);
            found.setLabel("v1");
            return null;
        });
        DifferentialSupport.inTransaction(P2, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantity(30.0);
            found.setLabel("v2");
            return null;
        });
        DifferentialSupport.inTransaction(P3, tx -> {
            findOn(id, biz(2026, 6, 1)).terminate();
            return null;
        });

        List<Map<String, Object>> h2 = allVersions(id);
        assertThat(h2.size())
                .as("history must contain more than the current slice:%n%s",
                        DifferentialSupport.describeRows(h2))
                .isGreaterThan(1);
        assertDistinctSortKeys(h2);

        Timestamp bizAsOf = biz(2026, 6, 1);
        // Half-open processing: as-of the FROM of a version sees that version. as-of its TO
        // (the next version's FROM) sees the successor. Infinity as-of is the special case
        // AsOfAttribute.dataMatches uses: skip the +1ms exclusive bump and require to == inf.
        assertThat(asOf(h2, bizAsOf, proc(P0)).get("quantity")).isEqualTo(Double.valueOf(10.0));
        assertThat(asOf(h2, bizAsOf, proc(P1)).get("quantity")).isEqualTo(Double.valueOf(20.0));
        assertThat(asOf(h2, bizAsOf, proc(P2)).get("quantity")).isEqualTo(Double.valueOf(30.0));
        assertThat(asOf(h2, bizAsOf, proc(P3)))
                .as("as-of the terminate processing FROM, June is gone")
                .isNull();
        assertThat(asOf(h2, bizAsOf, DifferentialSupport.infinity()))
                .as("after terminate, current processing has no row at June")
                .isNull();

        store.assertAgrees("balanceId", id, h2);

        List<Map<String, Object>> ddbRows = store.readPartition("balanceId", id);
        assertThat(asOf(ddbRows, bizAsOf, proc(P0)).get("quantity")).isEqualTo(Double.valueOf(10.0));
        assertThat(asOf(ddbRows, bizAsOf, proc(P1)).get("quantity")).isEqualTo(Double.valueOf(20.0));
        assertThat(asOf(ddbRows, bizAsOf, proc(P2)).get("quantity")).isEqualTo(Double.valueOf(30.0));
        assertThat(asOf(ddbRows, bizAsOf, proc(P3))).isNull();
        assertThat(asOf(ddbRows, bizAsOf, DifferentialSupport.infinity())).isNull();
    }

    private static void insertOpening(int id, double quantity, String label) {
        insertOpeningAt(id, biz(2026, 1, 1), quantity, label);
    }

    private static void insertOpeningAt(int id, Timestamp businessDate, double quantity, String label) {
        DiffBalance b = new DiffBalance(businessDate);
        b.setBalanceId(id);
        b.setQuantity(quantity);
        b.setLabel(label);
        b.insert();
    }

    private static DiffBalance findOn(int id, Timestamp businessDate) {
        DiffBalance found = DiffBalanceFinder.findOne(
                DiffBalanceFinder.balanceId().eq(id)
                        .and(DiffBalanceFinder.businessDate().eq(businessDate)));
        assertThat(found).as("expected DiffBalance %s at %s", Integer.valueOf(id), businessDate).isNotNull();
        return found;
    }

    private static List<Map<String, Object>> allVersions(int id) {
        DiffBalanceList list = DiffBalanceFinder.findMany(
                DiffBalanceFinder.balanceId().eq(id)
                        .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                        .and(DiffBalanceFinder.processingDate().equalsEdgePoint()));
        return DifferentialSupport.extract(DiffBalanceFinder.getFinderInstance(), list);
    }

    /**
     * Reladomo {@code AsOfAttribute.dataMatches} (javap 18.1.0, {@code toIsInclusive=false}):
     * <ul>
     *   <li>as-of infinity: skip the +1ms exclusive bump, so the predicate is
     *       {@code from <= inf && inf <= to} — only a row whose TO is the infinity sentinel.</li>
     *   <li>any other as-of: {@code from <= asOf && asOf < to} (implemented as asOf+1ms against
     *       an inclusive upper bound).</li>
     * </ul>
     * Returns null when no rectangle covers the point — including zero-length segments and dates
     * after terminate.
     */
    private static Map<String, Object> asOf(List<Map<String, Object>> rows,
                                            Timestamp businessAsOf,
                                            Timestamp processingAsOf) {
        Map<String, Object> match = null;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            if (contains(row, "businessDateFrom", "businessDateTo", businessAsOf)
                    && contains(row, "processingDateFrom", "processingDateTo", processingAsOf)) {
                if (match != null) {
                    throw new AssertionError("two rectangles cover the same as-of point:"
                            + DifferentialSupport.describeRows(rows));
                }
                match = row;
            }
        }
        return match;
    }

    private static boolean contains(Map<String, Object> row, String fromKey, String toKey, Timestamp asOf) {
        Timestamp from = (Timestamp) row.get(fromKey);
        Timestamp to = (Timestamp) row.get(toKey);
        if (from == null || to == null || asOf == null) {
            return false;
        }
        long t = asOf.getTime();
        long fromT = from.getTime();
        long toT = to.getTime();
        long inf = DifferentialSupport.infinity().getTime();
        if (t == inf) {
            return fromT <= t && t <= toT;
        }
        return fromT <= t && t < toT;
    }

    private static void assertDistinctSortKeys(List<Map<String, Object>> rows) {
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        StringBuilder dump = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Timestamp processingFrom = (Timestamp) row.get("processingDateFrom");
            Timestamp businessFrom = (Timestamp) row.get("businessDateFrom");
            String sk = keys.sortKey(store.mapping, processingFrom, businessFrom);
            Integer prev = counts.get(sk);
            counts.put(sk, Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));
            dump.append("  [").append(i).append("] sk=").append(sk)
                    .append(" businessDateTo=").append(row.get("businessDateTo"))
                    .append(" processingDateTo=").append(row.get("processingDateTo"))
                    .append('\n');
        }
        assertThat(counts.size())
                .as("H2 rectangles must have distinct v1 sort keys (FROM-only). A corner-share is a "
                                + "genuine pk+sk collision:%n%s%s",
                        DifferentialSupport.describeRows(rows), dump.toString())
                .isEqualTo(rows.size());
    }

    private static Timestamp biz(int y, int mo, int d) {
        return DifferentialSupport.utc(y, mo, d);
    }

    private static Timestamp proc(long millis) {
        return new Timestamp(millis);
    }
}
