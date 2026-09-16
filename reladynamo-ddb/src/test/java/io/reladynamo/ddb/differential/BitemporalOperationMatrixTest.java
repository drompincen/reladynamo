package io.reladynamo.ddb.differential;

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

/**
 * One differential test per {@code TemporalDirector} operation reachable from {@code DiffBalance}.
 * Processing time is pinned so the row sets are deterministic.
 *
 * <p>The 14 director operations (javap, reladomo 18.1.0) are: {@code insert},
 * {@code insertUntil}, {@code insertWithIncrement}, {@code insertWithIncrementUntil},
 * {@code update}, {@code updateUntil}, {@code increment}, {@code incrementUntil},
 * {@code inPlaceUpdate}, {@code terminate}, {@code terminateUntil}, {@code purge},
 * {@code inactivateForArchiving}, {@code insertForRecovery}.
 */
class BitemporalOperationMatrixTest {

    private static final long P0 = DifferentialSupport.utc(2020, 3, 1, 12, 0, 0, 0).getTime();
    private static final long P1 = DifferentialSupport.utc(2020, 3, 1, 12, 0, 1, 0).getTime();
    private static final long P2 = DifferentialSupport.utc(2020, 3, 1, 12, 0, 2, 0).getTime();

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
    void insert_round_trips_identically() {
        int id = 901;
        insertOpening(id, P0, 10.0, "insert");
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void insertUntil_round_trips_identically() {
        int id = 902;
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance b = new DiffBalance(biz(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(10.0);
            b.setLabel("until");
            b.insertUntil(biz(2026, 6, 1));
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void insertWithIncrement_round_trips_identically() {
        // Reladomo rejects insertWithIncrement when a current row already covers the as-of date
        // ("cannot insert data. data already exists"). The operation fills a gap *before* a later
        // segment and increments that later segment. Verified against GenericBiTemporalDirector
        // 18.1.0, not assumed from the method name.
        int id = 903;
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance later = new DiffBalance(biz(2026, 6, 1));
            later.setBalanceId(id);
            later.setQuantity(100.0);
            later.setLabel("later");
            later.insert();
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance add = new DiffBalance(biz(2026, 1, 1));
            add.setBalanceId(id);
            add.setQuantity(25.0);
            add.setLabel("incremented");
            add.insertWithIncrement();
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void insertWithIncrementUntil_round_trips_identically() {
        // Same gap-then-increment shape as insertWithIncrement, with an exclusive business until.
        // The later segment starts at 2026-06-01 so the until-window [2026-01-01, 2026-09-01)
        // both fills the gap and splits/increments the existing row.
        // H2 produces four rectangles: inactivated original (P0→P1), tail after until,
        // incremented window, and the gap fill. All four FROM pairs are distinct, so the
        // v1 FROM-only sort key stores them without collision (see CONFORMANCE-FINDINGS #7).
        int id = 904;
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance later = new DiffBalance(biz(2026, 6, 1));
            later.setBalanceId(id);
            later.setQuantity(100.0);
            later.setLabel("later");
            later.insert();
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance add = new DiffBalance(biz(2026, 1, 1));
            add.setBalanceId(id);
            add.setQuantity(25.0);
            add.setLabel("inc-until");
            add.insertWithIncrementUntil(biz(2026, 9, 1));
            return null;
        });
        List<Map<String, Object>> reference = allVersions(id);
        // Finding 1: the differ now keys on TO as well as FROM. Finding 7: this operation, in two
        // processing transactions, still produces distinct FROM pairs — so the v1 FROM-only sort
        // key does not collide. Assert that explicitly; a corner-share would be a real divergence
        // (DynamoDB would overwrite) and must fail with the row dump, not be papered over.
        assertThat(uniqueSortKeyCount(reference))
                .as("insertWithIncrementUntil H2 rectangles must have distinct v1 sort keys"
                        + " (FROM-only). A corner-share is a genuine pk+sk collision:%n%s%s",
                        DifferentialSupport.describeRows(reference), describeSortKeys(reference))
                .isEqualTo(reference.size());
        store.assertAgrees("balanceId", id, reference);
    }

    @Test
    void update_from_business_date_round_trips_identically() {
        int id = 905;
        insertOpening(id, P0, 10.0, "original");
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantity(50.0);
            found.setLabel("updated");
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void updateUntil_round_trips_identically() {
        int id = 906;
        insertOpening(id, P0, 10.0, "original");
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffBalance found = findOn(id, biz(2026, 6, 1));
            found.setQuantityUntil(77.0, biz(2026, 9, 1));
            found.setLabelUntil("window", biz(2026, 9, 1));
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void increment_round_trips_identically() {
        int id = 907;
        insertOpening(id, P0, 10.0, "original");
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 6, 1)).incrementQuantity(3.5);
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void incrementUntil_round_trips_identically() {
        int id = 908;
        insertOpening(id, P0, 10.0, "original");
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 6, 1)).incrementQuantityUntil(1.5, biz(2026, 9, 1));
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void inPlaceUpdate_round_trips_identically() {
        // Reladomo does not route a plain setter through TemporalDirector.inPlaceUpdate.
        // Code generation emits setNoteUsingInPlaceUpdate when the XML attribute has
        // inPlaceUpdate="true". javap/generated DiffBalanceAbstract 18.1.0.
        int id = 909;
        insertOpening(id, P0, 10.0, "original");
        List<Map<String, Object>> before = allVersions(id);
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 6, 1)).setNoteUsingInPlaceUpdate("in-place");
            return null;
        });
        List<Map<String, Object>> after = allVersions(id);
        assertThat(after.size())
                .as("inPlaceUpdate must not open a new processing-time version")
                .isEqualTo(before.size());
        boolean sawNote = false;
        for (int i = 0; i < after.size(); i++) {
            if ("in-place".equals(after.get(i).get("note"))) {
                sawNote = true;
                break;
            }
        }
        assertThat(sawNote)
                .as("inPlaceUpdate must persist the new note on the existing rectangle:%n%s",
                        DifferentialSupport.describeRows(after))
                .isTrue();
        store.assertAgrees("balanceId", id, after);
    }

    @Test
    void terminate_round_trips_identically() {
        int id = 910;
        insertOpening(id, P0, 10.0, "to-terminate");
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 6, 1)).terminate();
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void terminateUntil_round_trips_identically() {
        int id = 911;
        insertOpening(id, P0, 10.0, "original");
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 6, 1)).terminateUntil(biz(2026, 9, 1));
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void purge_removes_every_version_in_both_stores() {
        int id = 912;
        insertOpening(id, P0, 10.0, "to-purge");
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 6, 1)).setQuantity(99.0);
            return null;
        });
        List<Map<String, Object>> before = allVersions(id);
        assertThat(before).isNotEmpty();
        store.push(before);

        DifferentialSupport.inTransaction(P2, tx -> {
            findOn(id, biz(2026, 6, 1)).purge();
            return null;
        });
        List<Map<String, Object>> after = allVersions(id);
        assertThat(after).as("H2 purge must physically erase the versions").isEmpty();
        store.assertAgreesAfterReplay("balanceId", id, after);
    }

    @Test
    void inactivateForArchiving_round_trips_identically() {
        int id = 913;
        insertOpening(id, P0, 10.0, "to-archive");
        DifferentialSupport.inTransaction(P1, tx -> {
            findOn(id, biz(2026, 6, 1)).inactivateForArchiving(
                    DifferentialSupport.utc(2020, 3, 1, 12, 0, 5, 0),
                    biz(2026, 12, 1));
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    @Test
    void insertForRecovery_round_trips_identically() {
        int id = 914;
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffBalance recovered = new DiffBalance(biz(2026, 1, 1), proc(P0));
            recovered.setBalanceId(id);
            recovered.setQuantity(42.0);
            recovered.setLabel("recovered");
            recovered.setBusinessDateFrom(biz(2026, 1, 1));
            recovered.setBusinessDateTo(DifferentialSupport.infinity());
            recovered.setProcessingDateFrom(proc(P0));
            recovered.setProcessingDateTo(DifferentialSupport.infinity());
            recovered.insertForRecovery();
            return null;
        });
        store.assertAgrees("balanceId", id, allVersions(id));
    }

    private static void insertOpening(int id, long processing, double quantity, String label) {
        DifferentialSupport.inTransaction(processing, tx -> {
            DiffBalance b = new DiffBalance(biz(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(quantity);
            b.setLabel(label);
            b.insert();
            return null;
        });
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

    private static Timestamp biz(int y, int mo, int d) {
        return DifferentialSupport.utc(y, mo, d);
    }

    private static Timestamp proc(long millis) {
        return new Timestamp(millis);
    }

    /**
     * The v1 sort key embeds only FROM timestamps. Two rectangles that share a corner (same FROMs,
     * different TOs) would collide on DynamoDB even when TemporalRowSetDiffer can tell them apart.
     * This fixture of insertWithIncrementUntil does not produce that shape: the four H2 rectangles
     * have four distinct FROM pairs, hence four sort keys.
     */
    private static int uniqueSortKeyCount(List<Map<String, Object>> rows) {
        return sortKeyCounts(rows).size();
    }

    private static Map<String, Integer> sortKeyCounts(List<Map<String, Object>> rows) {
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Timestamp processingFrom = (Timestamp) row.get("processingDateFrom");
            Timestamp businessFrom = (Timestamp) row.get("businessDateFrom");
            String sk = keys.sortKey(store.mapping, processingFrom, businessFrom);
            Integer prev = counts.get(sk);
            counts.put(sk, Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));
        }
        return counts;
    }

    private static String describeSortKeys(List<Map<String, Object>> rows) {
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Map<String, Integer> counts = sortKeyCounts(rows);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Timestamp processingFrom = (Timestamp) row.get("processingDateFrom");
            Timestamp businessFrom = (Timestamp) row.get("businessDateFrom");
            String sk = keys.sortKey(store.mapping, processingFrom, businessFrom);
            sb.append("  [").append(i).append("] sk=").append(sk)
                    .append(" businessDateTo=").append(row.get("businessDateTo"))
                    .append(" processingDateTo=").append(row.get("processingDateTo"))
                    .append('\n');
        }
        sb.append("unique sort keys: ").append(counts.size())
                .append(" of ").append(rows.size()).append(" row(s)\n");
        return sb.toString();
    }
}
