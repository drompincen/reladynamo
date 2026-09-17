package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraDatedTransactionalObject;
import com.gs.fw.common.mithra.MithraList;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffEntry;
import io.reladynamo.ddb.differential.domain.DiffEntryFinder;
import io.reladynamo.ddb.differential.domain.DiffTag;
import io.reladynamo.ddb.differential.domain.DiffTagFinder;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relationship graph the differential relationship tests navigate, plus the plumbing that
 * lets the <em>same</em> finder call run against H2 and against DynamoDB.
 *
 * <p>Shape. {@code DiffBalance 1--* DiffEntry 1--* DiffTag}. The first hop is declared on the
 * parent (one-to-many with a reverse {@code balance}); the second is declared on the child as
 * many-to-one with a reverse {@code tags}, which is how the demo object models express a chain
 * (InvoiceLine.invoice / reverse "lines"). Both declaration directions therefore participate, and
 * {@code entries().tags()} is a genuine two-level deep fetch.
 *
 * <p>History. Everything is inserted in one transaction at processing time {@link #P0} with
 * business date {@link #B0}. A second transaction at {@link #P1} changes the graph effective
 * {@link #B1}: one child's amount is corrected and another child is terminated. The three as-of
 * points {@code (B0, infinity)}, {@code (B1, infinity)} and {@code (B1, }{@link #P_MID}{@code )}
 * therefore have three <em>different</em> right answers, which is the point — a navigation that
 * ignored the as-of dates would pass a single-date test.
 *
 * <p>Write path is H2: Reladomo computes the history, every version is mirrored into DynamoDB
 * through {@link DynamoDbWriter}, and only the <em>read</em> is compared. Java 11 baseline.
 */
final class RelationshipFixture implements AutoCloseable {

    static final int[] PARENTS = {8201, 8202, 8203, 8204};
    /** A parent with no children at any date: deep fetch must yield an empty list, not a refusal. */
    static final int LONELY_PARENT = 8205;
    static final int CHILDREN_PER_PARENT = 3;
    static final int TAGS_PER_CHILD = 2;
    static final int FIRST_ENTRY = 9201;
    static final int LAST_ENTRY = FIRST_ENTRY + (PARENTS.length * CHILDREN_PER_PARENT) - 1;
    static final int FIRST_TAG = 9401;
    static final int LAST_TAG = FIRST_TAG + (PARENTS.length * CHILDREN_PER_PARENT * TAGS_PER_CHILD) - 1;

    /** Corrected at {@link #B1}: its amount differs by business date and by processing date. */
    static final int UPDATED_ENTRY = 9201;
    static final double UPDATED_AMOUNT = 77.5;
    /** Terminated at {@link #B1}: parent 8202 has three children at B0 and two at B1. */
    static final int TERMINATED_ENTRY = 9205;
    static final int TERMINATED_ENTRY_PARENT = 8202;

    static final long P0 = DifferentialSupport.utc(2020, 3, 1, 12, 0, 0, 0).getTime();
    static final long P1 = DifferentialSupport.utc(2020, 6, 1, 12, 0, 0, 0).getTime();
    /** Strictly inside [P0, P1): a past processing date that still sees the original graph. */
    static final Timestamp P_MID = DifferentialSupport.utc(2020, 4, 1, 12, 0, 0, 0);
    static final Timestamp B0 = DifferentialSupport.utc(2026, 1, 1);
    static final Timestamp B1 = DifferentialSupport.utc(2026, 6, 1);

    static final String GSI_ENTRY_FK = "gsi_balanceId";
    static final String GSI_TAG_FK = "gsi_entryId";

    /** Association marker; ends in "Id" so the differ's identity includes it. */
    static final String NAV_PARENT = "navParentId";

    private final LocalDynamoDb ddb;
    private final RelationshipDifferentialTest.RequestCounter counter;

    private final EntityMapping balanceMapping;
    private final EntityMapping entryMapping;
    private final EntityMapping tagMapping;

    private final DynamoDbWriter balanceWriter;
    private final DynamoDbWriter entryWriter;
    private final DynamoDbWriter tagWriter;

    private final DynamoDbPersister balanceAdapter;
    private final DynamoDbPersister entryAdapter;
    private final DynamoDbPersister tagAdapter;
    /** Same physical table, but a design that declares no GSI: the no-access-path configuration. */
    private final DynamoDbPersister entryAdapterWithoutGsi;

    private final MithraAbstractObjectPortal balancePortal;
    private final MithraAbstractObjectPortal entryPortal;
    private final MithraAbstractObjectPortal tagPortal;
    private final MithraObjectReader balanceJdbc;
    private final MithraObjectReader entryJdbc;
    private final MithraObjectReader tagJdbc;

    private RelationshipFixture() {
        DifferentialSupport.boot();
        this.ddb = LocalDynamoDb.start();
        this.counter = new RelationshipDifferentialTest.RequestCounter();
        DynamoDbClient counting = counter.wrap(ddb.client());

        this.balanceMapping = DifferentialSupport.parseMapping("/reladomo/models/DiffBalance.xml");
        this.entryMapping = DifferentialSupport.parseMapping("/reladomo/models/DiffEntry.xml");
        this.tagMapping = DifferentialSupport.parseMapping("/reladomo/models/DiffTag.xml");
        ItemCodec balanceCodec = new ItemCodec(balanceMapping);
        ItemCodec entryCodec = new ItemCodec(entryMapping);
        ItemCodec tagCodec = new ItemCodec(tagMapping);

        PhysicalDesign balanceDesign = PhysicalDesign.builder(balanceMapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance()).build();
        PhysicalDesign entryDesign = PhysicalDesign.builder(entryMapping)
                .infinityFrom(DiffEntryFinder.getFinderInstance())
                .addGsi(GsiSpec.foreignKey(GSI_ENTRY_FK, "balanceId"))
                .build();
        PhysicalDesign tagDesign = PhysicalDesign.builder(tagMapping)
                .infinityFrom(DiffTagFinder.getFinderInstance())
                .addGsi(GsiSpec.foreignKey(GSI_TAG_FK, "entryId"))
                .build();
        PhysicalDesign entryDesignNoGsi = PhysicalDesign.builder(entryMapping)
                .infinityFrom(DiffEntryFinder.getFinderInstance()).build();

        DifferentialSupport.createPkSkTable(ddb, balanceMapping.tableName());
        new TableCreator(ddb.client()).create(entryDesign);
        new TableCreator(ddb.client()).create(tagDesign);

        this.balanceWriter = new DynamoDbWriter(ddb.client(), balanceMapping, balanceCodec,
                new DefaultKeyStrategy());
        this.entryWriter = new DynamoDbWriter(ddb.client(), entryMapping, entryCodec,
                new DefaultKeyStrategy(), entryDesign.gsis());
        this.tagWriter = new DynamoDbWriter(ddb.client(), tagMapping, tagCodec,
                new DefaultKeyStrategy(), tagDesign.gsis());

        PlannerConfig config = PlannerConfig.builder().build();
        this.balanceAdapter = new DynamoDbPersister(DiffBalanceFinder.getFinderInstance(),
                balanceMapping, balanceWriter, new QueryPlanner(),
                new QueryPlanExecutor(counting, balanceCodec), balanceDesign, config);
        this.entryAdapter = new DynamoDbPersister(DiffEntryFinder.getFinderInstance(),
                entryMapping, entryWriter, new QueryPlanner(),
                new QueryPlanExecutor(counting, entryCodec), entryDesign, config);
        this.tagAdapter = new DynamoDbPersister(DiffTagFinder.getFinderInstance(),
                tagMapping, tagWriter, new QueryPlanner(),
                new QueryPlanExecutor(counting, tagCodec), tagDesign, config);
        this.entryAdapterWithoutGsi = new DynamoDbPersister(DiffEntryFinder.getFinderInstance(),
                entryMapping, entryWriter, new QueryPlanner(),
                new QueryPlanExecutor(counting, entryCodec), entryDesignNoGsi, config);

        this.balancePortal = (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();
        this.entryPortal = (MithraAbstractObjectPortal) DiffEntryFinder.getMithraObjectPortal();
        this.tagPortal = (MithraAbstractObjectPortal) DiffTagFinder.getMithraObjectPortal();
        this.balanceJdbc = balancePortal.getDatabaseObject();
        this.entryJdbc = entryPortal.getDatabaseObject();
        this.tagJdbc = tagPortal.getDatabaseObject();

        seed();
        mutate();
        mirrorEveryVersion();
    }

    static RelationshipFixture open() {
        return new RelationshipFixture();
    }

    @Override
    public void close() {
        bindH2();
        ddb.close();
    }

    // --- seeding ------------------------------------------------------------------

    private void seed() {
        DifferentialSupport.inTransaction(P0, tx -> {
            int entryId = FIRST_ENTRY;
            int tagId = FIRST_TAG;
            for (int p = 0; p < PARENTS.length; p++) {
                DiffBalance parent = new DiffBalance(B0);
                parent.setBalanceId(PARENTS[p]);
                parent.setQuantity(200.0 + p);
                parent.setLabel("graph-parent-" + PARENTS[p]);
                parent.insert();
                for (int c = 0; c < CHILDREN_PER_PARENT; c++) {
                    DiffEntry child = new DiffEntry(B0);
                    child.setEntryId(entryId);
                    child.setBalanceId(PARENTS[p]);
                    child.setAmount(10.0 + c + (p * 0.25));
                    child.setLabel("graph-child-" + entryId);
                    child.insert();
                    for (int t = 0; t < TAGS_PER_CHILD; t++) {
                        DiffTag tag = new DiffTag(B0);
                        tag.setTagId(tagId);
                        tag.setEntryId(entryId);
                        tag.setWeight(0.5 + t);
                        tag.setLabel("graph-tag-" + tagId);
                        tag.insert();
                        tagId++;
                    }
                    entryId++;
                }
            }
            DiffBalance lonely = new DiffBalance(B0);
            lonely.setBalanceId(LONELY_PARENT);
            lonely.setQuantity(0.0);
            lonely.setLabel("graph-parent-without-children");
            lonely.insert();
            return null;
        });
    }

    private void mutate() {
        DifferentialSupport.inTransaction(P1, tx -> {
            DiffEntry corrected = DiffEntryFinder.findOne(
                    DiffEntryFinder.entryId().eq(UPDATED_ENTRY)
                            .and(DiffEntryFinder.businessDate().eq(B1)));
            if (corrected == null) {
                throw new IllegalStateException("seed missing: entry " + UPDATED_ENTRY + " at " + B1);
            }
            corrected.setAmount(UPDATED_AMOUNT);
            DiffEntry doomed = DiffEntryFinder.findOne(
                    DiffEntryFinder.entryId().eq(TERMINATED_ENTRY)
                            .and(DiffEntryFinder.businessDate().eq(B1)));
            if (doomed == null) {
                throw new IllegalStateException("seed missing: entry " + TERMINATED_ENTRY + " at " + B1);
            }
            doomed.terminate();
            return null;
        });
    }

    /**
     * Every version of every row, including the ones the P1 transaction closed off, mirrored into
     * DynamoDB. A mirror of only the current versions would make the past-date navigations vacuous.
     */
    private void mirrorEveryVersion() {
        mirror(DiffBalanceFinder.getFinderInstance(),
                DiffBalanceFinder.findMany(balanceEdgePoint()), balanceWriter);
        mirror(DiffEntryFinder.getFinderInstance(),
                DiffEntryFinder.findMany(entryEdgePoint()), entryWriter);
        mirror(DiffTagFinder.getFinderInstance(),
                DiffTagFinder.findMany(tagEdgePoint()), tagWriter);
    }

    private static void mirror(RelatedFinder finder, MithraList list, DynamoDbWriter writer) {
        list.setBypassCache(true);
        for (int i = 0; i < list.size(); i++) {
            writer.upsert(MithraDataAccessor.extract(finder,
                    ((MithraDatedTransactionalObject) list.get(i)).zGetCurrentData()));
        }
    }

    static Operation balanceEdgePoint() {
        return DiffBalanceFinder.balanceId().greaterThanEquals(PARENTS[0])
                .and(DiffBalanceFinder.balanceId().lessThanEquals(LONELY_PARENT))
                .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                .and(DiffBalanceFinder.processingDate().equalsEdgePoint());
    }

    static Operation entryEdgePoint() {
        return DiffEntryFinder.entryId().greaterThanEquals(FIRST_ENTRY)
                .and(DiffEntryFinder.entryId().lessThanEquals(LAST_ENTRY))
                .and(DiffEntryFinder.businessDate().equalsEdgePoint())
                .and(DiffEntryFinder.processingDate().equalsEdgePoint());
    }

    static Operation tagEdgePoint() {
        return DiffTagFinder.tagId().greaterThanEquals(FIRST_TAG)
                .and(DiffTagFinder.tagId().lessThanEquals(LAST_TAG))
                .and(DiffTagFinder.businessDate().equalsEdgePoint())
                .and(DiffTagFinder.processingDate().equalsEdgePoint());
    }

    // --- store binding ------------------------------------------------------------

    /**
     * Cold on every portal. A relationship resolved from a warm child cache proves nothing about
     * the adapter, and the parent's bypass flag does not reach the child portal.
     */
    void clearCaches() {
        balancePortal.getCache().clear();
        balancePortal.clearQueryCache();
        entryPortal.getCache().clear();
        entryPortal.clearQueryCache();
        tagPortal.getCache().clear();
        tagPortal.clearQueryCache();
    }

    void bindH2() {
        clearCaches();
        balancePortal.setMithraObjectReader(balanceJdbc);
        entryPortal.setMithraObjectReader(entryJdbc);
        tagPortal.setMithraObjectReader(tagJdbc);
    }

    void bindDynamo() {
        clearCaches();
        balancePortal.setMithraObjectReader(balanceAdapter);
        entryPortal.setMithraObjectReader(entryAdapter);
        tagPortal.setMithraObjectReader(tagAdapter);
    }

    /** Child portal bound to a design with no foreign-key GSI; parent and tag stay normal. */
    void bindDynamoWithoutEntryGsi() {
        clearCaches();
        balancePortal.setMithraObjectReader(balanceAdapter);
        entryPortal.setMithraObjectReader(entryAdapterWithoutGsi);
        tagPortal.setMithraObjectReader(tagAdapter);
    }

    RelationshipDifferentialTest.RequestCounter counter() {
        return counter;
    }

    DynamoDbClient rawClient() {
        return ddb.client();
    }

    EntityMapping entryMapping() {
        return entryMapping;
    }

    // --- row extraction -----------------------------------------------------------

    /** Every persistent attribute plus the four temporal boundaries, tagged with its parent. */
    static Map<String, Object> rowOf(RelatedFinder finder, MithraDatedTransactionalObject object,
                                     int parentId) {
        Map<String, Object> row = MithraDataAccessor.extract(finder, object.zGetCurrentData());
        row.put(NAV_PARENT, Integer.valueOf(parentId));
        return row;
    }

    static List<Map<String, Object>> rowsOf(RelatedFinder finder, MithraList list, int parentId) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); i++) {
            rows.add(rowOf(finder, (MithraDatedTransactionalObject) list.get(i), parentId));
        }
        return rows;
    }

    // --- assertions ---------------------------------------------------------------

    static void assertGraphAgrees(String what, List<Map<String, Object>> reference,
                                          List<Map<String, Object>> adapter) {
        assertThat(adapter)
                .as("%s: DynamoDB returned %s rows, H2 returned %s", what,
                        Integer.valueOf(adapter.size()), Integer.valueOf(reference.size()))
                .hasSameSizeAs(reference);
        RowSetDiff diff = TemporalRowSetDiffer.compare(reference, adapter);
        assertThat(diff.isIdentical())
                .as("%s disagreed between stores:%n%s%nH2:%n%s%nDDB:%n%s", what, diff.describe(),
                        DifferentialSupport.describeRows(reference),
                        DifferentialSupport.describeRows(adapter))
                .isTrue();
    }

    static Double amountOf(List<Map<String, Object>> rows, int entryId) {
        for (int i = 0; i < rows.size(); i++) {
            Object id = rows.get(i).get("entryId");
            if (id instanceof Integer && ((Integer) id).intValue() == entryId) {
                return (Double) rows.get(i).get("amount");
            }
        }
        throw new AssertionError("entry " + entryId + " is not in the graph: " + rows);
    }

    static List<Map<String, Object>> childrenOfParent(List<Map<String, Object>> rows,
                                                              int parentId) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rows.size(); i++) {
            Object p = rows.get(i).get(RelationshipFixture.NAV_PARENT);
            if (p instanceof Integer && ((Integer) p).intValue() == parentId) {
                out.add(rows.get(i));
            }
        }
        return out;
    }

    /** Unwraps transparent framework wrappers only; the whole chain is searched, never guessed. */
    static <T extends Throwable> T causeOfType(Throwable thrown, Class<T> type) {
        Throwable t = thrown;
        while (t != null) {
            if (type.isInstance(t)) {
                return type.cast(t);
            }
            t = t.getCause();
        }
        return null;
    }
}
