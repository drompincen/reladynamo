package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraBusinessException;
import io.reladynamo.ddb.differential.DifferentialSupport.Store;
import io.reladynamo.ddb.differential.domain.DiffAudit;
import io.reladynamo.ddb.differential.domain.DiffAuditAbstract;
import io.reladynamo.ddb.differential.domain.DiffAuditFinder;
import io.reladynamo.ddb.differential.domain.DiffAuditList;
import io.reladynamo.ddb.differential.domain.DiffBalanceAbstract;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit-only flavour: {@code AuditOnlyTemporalDirector}, processingDate only.
 *
 * <p>The generated API is narrower than the director interface. {@code DiffAuditAbstract}
 * (reladomo 18.1.0) emits {@code insertUntil} / {@code terminateUntil} as throwing stubs, and does
 * not emit {@code setXUntil}, {@code increment*}, or {@code insertWithIncrement*}. Those absences
 * are asserted against the generated class, not guessed from the director.
 */
class AuditOnlyDifferentialTest {

    private static final long P0 = DifferentialSupport.utc(2020, 4, 1, 8, 0, 0, 0).getTime();
    private static final long P1 = DifferentialSupport.utc(2020, 4, 1, 8, 0, 1, 0).getTime();
    private static final long P2 = DifferentialSupport.utc(2020, 4, 1, 8, 0, 2, 0).getTime();

    private static LocalDynamoDb ddb;
    private static Store store;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        store = DifferentialSupport.openStore(ddb, "/reladomo/models/DiffAudit.xml");
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void insert_round_trips_identically() {
        int id = 100;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "opening");
            return null;
        });
        store.assertAgrees("auditId", id, allVersions(id));
    }

    @Test
    void update_preserves_processing_history_in_both_stores() {
        int id = 101;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "original");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffAudit found = current(id);
            found.setQuantity(99.0);
            found.setLabel("corrected");
            return null;
        });
        List<Map<String, Object>> rows = allVersions(id);
        assertThat(rows.size()).as("audit-only update must keep the prior processing version").isGreaterThan(1);
        store.assertAgrees("auditId", id, rows);
    }

    @Test
    void inPlaceUpdate_round_trips_identically() {
        // Reladomo does not route a plain setter through TemporalDirector.inPlaceUpdate.
        // Code generation emits setNoteUsingInPlaceUpdate when the XML attribute has
        // inPlaceUpdate="true". Generated DiffAuditAbstract, reladomo 18.1.0.
        int id = 103;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "original");
            return null;
        });
        List<Map<String, Object>> before = allVersions(id);
        DifferentialSupport.inTransaction(P1, tx -> {
            current(id).setNoteUsingInPlaceUpdate("in-place");
            return null;
        });
        List<Map<String, Object>> after = allVersions(id);
        assertThat(after.size())
                .as("audit-only inPlaceUpdate must not open a new processing-time version:%n%s",
                        DifferentialSupport.describeRows(after))
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
        store.assertAgrees("auditId", id, after);
    }

    @Test
    void terminate_round_trips_identically() {
        int id = 104;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "to-terminate");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            current(id).terminate();
            return null;
        });
        store.assertAgrees("auditId", id, allVersions(id));
    }

    @Test
    void purge_removes_every_version_in_both_stores() {
        int id = 105;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "to-purge");
            return null;
        });
        List<Map<String, Object>> before = allVersions(id);
        assertThat(before).isNotEmpty();
        store.push(before);
        DifferentialSupport.inTransaction(P1, tx -> {
            current(id).purge();
            return null;
        });
        List<Map<String, Object>> after = allVersions(id);
        assertThat(after).as("H2 purge must physically erase the versions").isEmpty();
        store.assertAgreesAfterReplay("auditId", id, after);
    }

    @Test
    void inactivateForArchiving_round_trips_identically() {
        int id = 106;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "to-archive");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            current(id).inactivateForArchiving(
                    DifferentialSupport.utc(2020, 4, 1, 8, 0, 5, 0),
                    DifferentialSupport.infinity());
            return null;
        });
        store.assertAgrees("auditId", id, allVersions(id));
    }

    @Test
    void insertForRecovery_round_trips_identically() {
        int id = 107;
        DifferentialSupport.inTransaction(P0, tx -> {
            DiffAudit recovered = new DiffAudit(proc(P0));
            recovered.setAuditId(id);
            recovered.setQuantity(7.0);
            recovered.setLabel("recovered");
            recovered.setProcessingDateFrom(proc(P0));
            recovered.setProcessingDateTo(DifferentialSupport.infinity());
            recovered.insertForRecovery();
            return null;
        });
        store.assertAgrees("auditId", id, allVersions(id));
    }

    @Test
    void insertUntil_is_rejected_without_a_business_date() {
        int id = 109;
        // Generated DiffAuditAbstract.insertUntil throws this stub; it never reaches
        // AuditOnlyTemporalDirector.insertUntil ("audit only objects do not provide insert until
        // functionality"). javap/generated sources, reladomo 18.1.0.
        assertThatThrownBy(() -> DifferentialSupport.inTransaction(P0, tx -> {
            DiffAudit a = new DiffAudit();
            a.setAuditId(id);
            a.setQuantity(1.0);
            a.setLabel("no-until");
            a.insertUntil(DifferentialSupport.utc(2026, 6, 1));
            return null;
        })).isInstanceOf(MithraBusinessException.class)
                .hasMessage("insertUntil is only supported for dated objects with a business date");
    }

    @Test
    void terminateUntil_is_rejected_without_a_business_date() {
        int id = 110;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 1.0, "no-until");
            return null;
        });
        assertThatThrownBy(() -> DifferentialSupport.inTransaction(P1, tx -> {
            current(id).terminateUntil(DifferentialSupport.utc(2026, 6, 1));
            return null;
        })).isInstanceOf(MithraBusinessException.class)
                .hasMessage("terminateUntil is only supported for dated objects with a business date");
    }

    @Test
    void insertWithIncrement_is_rejected_without_a_business_date() {
        int id = 108;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "base");
            return null;
        });
        // Not generated on DiffAuditAbstract. Inherited from MithraDatedTransactionalObjectImpl,
        // so the call reaches AuditOnlyTemporalDirector (javap 18.1.0).
        assertThatThrownBy(() -> DifferentialSupport.inTransaction(P1, tx -> {
            DiffAudit add = new DiffAudit();
            add.setAuditId(id);
            add.setQuantity(4.0);
            add.setLabel("inc");
            add.insertWithIncrement();
            return null;
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("audit only objects do not provide insert with increment");
    }

    @Test
    void insertWithIncrementUntil_is_rejected_without_a_business_date() {
        int id = 111;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "base");
            return null;
        });
        assertThatThrownBy(() -> DifferentialSupport.inTransaction(P1, tx -> {
            DiffAudit add = new DiffAudit();
            add.setAuditId(id);
            add.setQuantity(4.0);
            add.setLabel("inc-until");
            add.insertWithIncrementUntil(DifferentialSupport.utc(2026, 6, 1));
            return null;
        })).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("audit only objects do not provide insert with increment");
    }

    @Test
    void chained_terminate_then_reinsert_round_trips() {
        int id = 112;
        DifferentialSupport.inTransaction(P0, tx -> {
            insertAudit(id, 10.0, "first");
            return null;
        });
        DifferentialSupport.inTransaction(P1, tx -> {
            current(id).terminate();
            return null;
        });
        DifferentialSupport.inTransaction(P2, tx -> {
            insertAudit(id, 20.0, "second");
            return null;
        });
        store.assertAgrees("auditId", id, allVersions(id));
    }

    /**
     * The generated API, not the director interface. {@code insertUntil} / {@code terminateUntil}
     * are declared on {@code DiffAuditAbstract} as throwing stubs. Value-mutating until / increment
     * methods are not declared at all — a stronger guarantee than a runtime rejection.
     *
     * <p>{@code insertWithIncrement} is inherited from {@code MithraDatedTransactionalObjectImpl}
     * and is therefore visible on {@code DiffAudit#getMethods()}; asserting its absence against the
     * public inherited surface is the wrong premise. Assert declared methods on the generated class.
     */
    @Test
    void audit_only_offers_insert_and_terminate_until_but_no_value_mutating_until() {
        Set<String> auditDeclared = declaredNames(DiffAuditAbstract.class);
        assertThat(auditDeclared)
                .as("generated DiffAuditAbstract must declare insertUntil/terminateUntil (+ cascade)")
                .contains("insertUntil", "terminateUntil", "cascadeInsertUntil", "cascadeTerminateUntil");
        assertThat(auditDeclared)
                .as("generated DiffAuditAbstract must not declare value-mutating until/increment")
                .doesNotContain(
                        "setQuantityUntil", "setLabelUntil", "setNoteUntil",
                        "incrementQuantity", "incrementQuantityUntil",
                        "insertWithIncrement", "insertWithIncrementUntil");
        assertThat(auditDeclared)
                .as("inPlaceUpdate is generated as setNoteUsingInPlaceUpdate, not a plain setter")
                .contains("setNoteUsingInPlaceUpdate");

        Set<String> bitemporalDeclared = declaredNames(DiffBalanceAbstract.class);
        assertThat(bitemporalDeclared)
                .as("bitemporal contrast: DiffBalanceAbstract does generate value-mutating until/increment")
                .contains("setQuantityUntil", "setLabelUntil", "setNoteUntil",
                        "incrementQuantity", "incrementQuantityUntil");
        assertThat(bitemporalDeclared)
                .as("bitemporal insertUntil/terminateUntil come from the superclass, not a generated stub")
                .doesNotContain("insertUntil", "terminateUntil");
    }

    private static Set<String> declaredNames(Class<?> type) {
        Set<String> names = new HashSet<String>();
        Method[] methods = type.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            names.add(methods[i].getName());
        }
        return names;
    }

    private static void insertAudit(int id, double quantity, String label) {
        DiffAudit a = new DiffAudit();
        a.setAuditId(id);
        a.setQuantity(quantity);
        a.setLabel(label);
        a.insert();
    }

    private static DiffAudit current(int id) {
        DiffAudit found = DiffAuditFinder.findOne(DiffAuditFinder.auditId().eq(id));
        assertThat(found).as("expected current DiffAudit %s", Integer.valueOf(id)).isNotNull();
        return found;
    }

    private static List<Map<String, Object>> allVersions(int id) {
        DiffAuditList list = DiffAuditFinder.findMany(
                DiffAuditFinder.auditId().eq(id)
                        .and(DiffAuditFinder.processingDate().equalsEdgePoint()));
        return DifferentialSupport.extract(DiffAuditFinder.getFinderInstance(), list);
    }

    private static Timestamp proc(long millis) {
        return new Timestamp(millis);
    }
}
