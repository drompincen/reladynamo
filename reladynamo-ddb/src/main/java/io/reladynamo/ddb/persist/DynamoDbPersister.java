package io.reladynamo.ddb.persist;

import com.gs.fw.common.mithra.HavingOperation;
import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.MithraDatedObject;
import com.gs.fw.common.mithra.MithraDatedTransactionalObject;
import com.gs.fw.common.mithra.MithraTransaction;
import com.gs.fw.common.mithra.MithraTransactionalObject;
import com.gs.fw.common.mithra.attribute.update.AttributeUpdateWrapper;
import com.gs.fw.common.mithra.behavior.txparticipation.TxParticipationMode;
import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.ResultSetParser;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import com.gs.fw.common.mithra.list.cursor.Cursor;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import com.gs.fw.common.mithra.querycache.CachedQuery;
import com.gs.fw.common.mithra.transaction.BatchUpdateOperation;
import com.gs.fw.common.mithra.transaction.MithraDatedObjectPersister;
import com.gs.fw.common.mithra.transaction.MultiUpdateOperation;
import com.gs.fw.common.mithra.util.Filter;
import com.gs.fw.common.mithra.util.RenewedCacheStats;
import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.gs.fw.common.mithra.transaction.InTransactionDatedTransactionalObject;
import com.gs.fw.common.mithra.finder.asofop.AsOfOperation;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.bridge.MithraDataFactory;
import io.reladynamo.core.bridge.MithraDataPopulator;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.PlanningPurpose;
import io.reladynamo.core.plan.PlanningRequest;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.exec.QueryPlanExecutor;

import com.gs.fw.common.mithra.behavior.txparticipation.MithraOptimisticLockException;
import com.gs.fw.common.mithra.cache.Cache;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The adapter's single seam into Reladomo.
 *
 * <p>Installed with {@code MithraAbstractObjectPortal.setMithraObjectReader(persister)}. That one call
 * binds both halves: {@code getMithraObjectPersister()} returns the same instance, proven by
 * {@code WritePathSpikeTest}. Implementing both interfaces on one class is therefore not a
 * convenience — it is what makes a single setter sufficient.
 *
 * <p><b>No bitemporal logic lives here.</b> Reladomo's {@code TemporalDirector} sits above and has
 * already decomposed {@code terminate} / {@code updateUntil} / {@code incrementUntil} into plain
 * inserts, updates and deletes of dated data objects that carry their own boundaries. This stores
 * what it is given. Any temporal reasoning added below this line would diverge from H2, which is the
 * one outcome the differential suite exists to prevent.
 *
 * <p>Java 11 baseline.
 */
public final class DynamoDbPersister implements MithraObjectReader, MithraDatedObjectPersister {

    private final RelatedFinder finder;
    private final EntityMapping mapping;
    private final DynamoDbWriter writer;
    private final QueryPlanner planner;
    private final QueryPlanExecutor executor;
    private final PhysicalDesign design;
    private final PlannerConfig plannerConfig;

    /** Write-only: read methods refuse by name. Useful while the read path is being wired. */
    public DynamoDbPersister(RelatedFinder finder, EntityMapping mapping, DynamoDbWriter writer) {
        this(finder, mapping, writer, null, null, null, null);
    }

    public DynamoDbPersister(RelatedFinder finder, EntityMapping mapping, DynamoDbWriter writer,
                             QueryPlanner planner, QueryPlanExecutor executor,
                             PhysicalDesign design, PlannerConfig plannerConfig) {
        if (finder == null || mapping == null || writer == null) {
            throw new IllegalArgumentException("finder, mapping and writer are all required");
        }
        this.finder = finder;
        this.mapping = mapping;
        this.writer = writer;
        writer.attachPortal(finder.getMithraObjectPortal());
        this.planner = planner;
        this.executor = executor;
        this.design = design;
        this.plannerConfig = plannerConfig;
    }

    // --- write path ------------------------------------------------------------------

    @Override
    public void insert(MithraDataObject data) {
        writer.insert(rowOf(data));
    }

    @Override
    public void delete(MithraDataObject data) {
        writer.delete(rowOf(data));
    }

    @Override
    public void purge(MithraDataObject data) {
        writer.purge(rowOf(data));
    }

    @Override
    public void batchInsert(List dataObjects, int bulkInsertThreshold) {
        writer.batchInsert(rowsOf(dataObjects));
    }

    @Override
    public void batchDelete(List dataObjects) {
        writer.batchDelete(rowsOf(dataObjects));
    }

    @Override
    public void batchDeleteQuietly(List dataObjects) {
        // "Quietly" means a missing or concurrently-changed item is not an error. Durability of
        // the rows that do match is still required — lock failures are swallowed per row, not
        // the whole batch.
        List<Map<String, Object>> rows = rowsOf(dataObjects);
        for (int i = 0; i < rows.size(); i++) {
            try {
                writer.delete(rows.get(i));
            } catch (MithraOptimisticLockException ignored) {
                // expected for quietly
            }
        }
    }

    @Override
    public void batchPurge(List dataObjects) {
        writer.batchDelete(rowsOf(dataObjects));
    }

    /**
     * Applies the wrapper's attribute change onto the current data, then replaces the item only
     * when the stored row still matches the committed prior ({@code zGetNonTxData}). Insert is
     * the wrong verb here: after R-02, {@code insert} refuses an existing {@code pk+sk}.
     */
    @Override
    public void update(MithraTransactionalObject object, AttributeUpdateWrapper wrapper) {
        List wrappers = new ArrayList();
        if (wrapper != null) {
            wrappers.add(wrapper);
        }
        update(object, wrappers);
    }

    @Override
    public void update(MithraTransactionalObject object, List updateWrappers) {
        MithraDataObject current = object.zGetCurrentData();
        if (current == null) {
            throw new IllegalArgumentException(
                    "update of " + mapping.className() + " has no current data");
        }
        MithraDataObject prior = object.zGetNonTxData();
        if (prior == null && updateWrappers != null && !updateWrappers.isEmpty()) {
            prior = ((AttributeUpdateWrapper) updateWrappers.get(0)).getDataToUpdate();
        }
        Map<String, Object> expectedPrior = prior != null ? rowOf(prior) : null;

        if (updateWrappers != null) {
            for (int i = 0; i < updateWrappers.size(); i++) {
                ((AttributeUpdateWrapper) updateWrappers.get(i)).updateData(current);
            }
        }
        Map<String, Object> newRow = rowOf(current);
        if (expectedPrior == null) {
            throw new MithraOptimisticLockException(
                    "update of " + mapping.className()
                            + " has no expected prior state to lock against",
                    true);
        }
        writer.update(newRow, expectedPrior);
    }

    // --- not yet implemented: named explicitly rather than returning something plausible ----

    /**
     * Plans the operation, executes it, and materialises real Reladomo objects from the returned
     * items:
     * <pre>item → ItemCodec.decode → MithraDataFactory → MithraDataPopulator → Cache</pre>
     *
     * <p>Objects go through the portal's cache rather than being handed back raw, so identity and
     * caching behave as they do on the relational path — two queries hitting the same row must return
     * the same instance, or callers comparing by reference silently break.
     */
    @Override
    public CachedQuery find(AnalyzedOperation op, OrderBy orderBy, boolean a, int b, int c,
                            boolean d, boolean e) {
        writer.beforeRead();
        requireReadPath("find");
        QueryPlan plan = planner.plan(new PlanningRequest(
                op, orderBy, design, plannerConfig, b, 1, PlanningPurpose.FIND));
        List<Map<String, Object>> rows = executor.execute(plan);

        // Resolve axis operations once. AsOfEq is a single timestamp for every row;
        // AsOfEdgePoint (equalsEdgePoint) inflates per row from that row's own edge.
        // Unqualified dated finds still refuse here — even when the result is empty —
        // so a missing as-of cannot silently become "no rows" or a guessed date.
        Operation[] axisOps = axisOperationsOf(op);
        Cache cache = finder.getMithraObjectPortal().getCache();
        List<Object> objects = new ArrayList<Object>(rows.size());
        for (Map<String, Object> row : rows) {
            MithraDataObject data = MithraDataFactory.newData(finder);
            MithraDataPopulator.populate(finder, data, row);
            Timestamp[] asOfDates = asOfDatesOf(axisOps, data);
            objects.add(asOfDates == null
                    ? cache.getObjectFromData(data)
                    : cache.getObjectFromData(data, asOfDates));
        }

        // The list holds the original operation. Reladomo then does
        // if (listOp != cached.getOperation()) list.zSetOperation(cached.getOperation()),
        // and zSetOperation requires equals() with the list's current op. Caching the
        // analyzed form (as-of injected) makes that equals() fail with "cannot change operation"
        // on findMany(); deepFetch(); size() — Classifier.classify.
        CachedQuery result = new CachedQuery(op.getOriginalOperation(), orderBy);
        result.setResult(objects);
        return result;
    }

    /**
     * Per-axis as-of operations from the analysed tree.
     *
     * <p>Accepts any {@link AsOfOperation}: {@code AsOfEqOperation} (a point in time, including
     * {@code equalsInfinity()}) and {@code AsOfEdgePointOperation} ({@code equalsEdgePoint()},
     * javap-verified on Reladomo 18.1.0). A from-range lives on the physical timestamp attribute,
     * not the as-of; Reladomo still requires an as-of on each axis, which edge-point supplies.
     *
     * <p>Returns {@code null} for a non-dated object. Refuses rather than guessing when a dated
     * object's operation carries no as-of at all: materialising at a default date would return
     * rows that look plausible and are silently the wrong version.
     */
    private Operation[] axisOperationsOf(AnalyzedOperation op) {
        AsOfAttribute[] asOf = finder.getAsOfAttributes();
        if (asOf == null || asOf.length == 0) {
            return null;
        }
        Operation analyzed = op.getAnalyzedOperation();
        Operation[] axes = new Operation[asOf.length];
        for (int i = 0; i < asOf.length; i++) {
            Operation axis = analyzed.zGetAsOfOp(asOf[i]);
            if (!(axis instanceof AsOfOperation)) {
                throw unqualifiedAsOf(asOf[i]);
            }
            axes[i] = axis;
        }
        return axes;
    }

    /**
     * The as-of dates a dated object must be materialised at.
     *
     * <p>{@code AsOfEqOperation.inflateAsOfDate} returns the query parameter (one date for every
     * row). {@code AsOfEdgePointOperation.inflateAsOfDate} reads this row's edge attribute — the
     * from-bound when {@code toIsInclusive} is false, which is DiffBalance/DiffAudit. Each
     * version therefore materialises at <em>its own</em> rectangle, so two rows that differ only
     * in {@code processingDateTo} cannot collapse into one cached current-version object.
     */
    private Timestamp[] asOfDatesOf(Operation[] axisOps, MithraDataObject data) {
        if (axisOps == null) {
            return null;
        }
        AsOfAttribute[] asOf = finder.getAsOfAttributes();
        Timestamp[] dates = new Timestamp[axisOps.length];
        for (int i = 0; i < axisOps.length; i++) {
            Timestamp date = ((AsOfOperation) axisOps[i]).inflateAsOfDate(data);
            if (date == null) {
                throw unqualifiedAsOf(asOf[i]);
            }
            dates[i] = date;
        }
        return dates;
    }

    private UnsupportedOperationException unqualifiedAsOf(AsOfAttribute axis) {
        return new UnsupportedOperationException(
                "cannot materialise " + mapping.className() + " without an as-of equality for '"
                        + axis.getAttributeName() + "'. Picking a default date would return "
                        + "rows that look right and are silently the wrong version.");
    }

    @Override
    public Cursor findCursor(AnalyzedOperation op, Filter f, OrderBy o, int a, boolean b, int c, boolean d) {
        throw notYet("findCursor");
    }

    /**
     * Planned and executed for real. Counts rows by running the plan and measuring the result, rather
     * than issuing DynamoDB's {@code Select.COUNT}: the plan may carry a residual predicate that only
     * this layer can evaluate, and a server-side count would silently ignore it and over-report.
     */
    @Override
    public int count(Operation op) {
        writer.beforeRead();
        requireReadPath("count");
        QueryPlan plan = planner.plan(new PlanningRequest(
                new AnalyzedOperation(op), null, design, plannerConfig, 0, 1,
                PlanningPurpose.FIND));
        return executor.execute(plan).size();
    }

    @Override
    public List computeFunction(Operation op, OrderBy orderBy, String s, ResultSetParser parser) {
        throw notYet("computeFunction");
    }

    /**
     * Re-read the single item addressed by {@code data}'s derived {@code pk+sk}.
     *
     * <p>{@code lockInDatabase} has no DynamoDB equivalent of {@code SELECT FOR UPDATE}.
     * It is ignored: the adapter offers no pessimistic lock (Tier 1). The re-read is
     * always strongly consistent, which is the strongest guarantee available.
     *
     * <p>Returns {@code null} when the item is gone, matching Reladomo's JDBC
     * "deleted underneath us" path. Do not Scan, and do not return {@code data}
     * unchanged — a no-op refresh is indistinguishable from a working one until it
     * matters.
     *
     * <p>A GetItem of committed state while this transaction has staged writes would
     * be a second isolation policy: {@code find} and {@code count} already refuse
     * that situation with {@code RELADYNAMO-TXN-006}. Refresh uses the same
     * {@code beforeRead} gate rather than inventing a per-item buffer overlay.
     */
    @Override
    public MithraDataObject refresh(MithraDataObject data, boolean lockInDatabase) {
        writer.beforeRead();
        if (data == null) {
            throw new IllegalArgumentException(
                    "refresh of " + mapping.className() + " requires a data object to address");
        }
        Map<String, Object> fresh = writer.getConsistent(rowOf(data));
        if (fresh == null) {
            return null;
        }
        MithraDataObject out = MithraDataFactory.newData(finder);
        MithraDataPopulator.populate(finder, out, fresh);
        return out;
    }

    /**
     * Dated equivalent of {@link #refresh}. Identity includes the temporal from-bounds
     * already on the object's current data, so this GetItem addresses <em>that</em> rectangle,
     * not "the current version" selected by as-of containment. Substituting a later
     * rectangle would look like a successful read of the wrong history.
     */
    @Override
    public MithraDataObject refreshDatedObject(MithraDatedObject object, boolean lockInDatabase) {
        if (object == null) {
            throw new IllegalArgumentException(
                    "refreshDatedObject of " + mapping.className() + " requires a dated object");
        }
        MithraDataObject data = object.zGetCurrentOrTransactionalData();
        if (data == null && object instanceof MithraDatedTransactionalObject) {
            data = ((MithraDatedTransactionalObject) object).zGetCurrentData();
        }
        if (data == null) {
            throw new IllegalArgumentException(
                    "refreshDatedObject of " + mapping.className()
                            + " has no current data to address; the sort key cannot be derived without it");
        }
        return refresh(data, lockInDatabase);
    }

    @Override
    public List findAggregatedData(Operation op, Map m, Map m1, HavingOperation h, boolean b, Class c) {
        throw notYet("findAggregatedData");
    }

    @Override
    public void loadFullCache() {
        throw notYet("loadFullCache");
    }

    @Override
    public void reloadFullCache() {
        throw notYet("reloadFullCache");
    }

    @Override
    public RenewedCacheStats renewCacheForOperation(Operation op) {
        throw notYet("renewCacheForOperation");
    }

    @Override
    public Map extractDatabaseIdentifiers(Operation op) {
        throw notYet("extractDatabaseIdentifiers");
    }

    @Override
    public Map extractDatabaseIdentifiers(Set sourceAttributeValueSet) {
        throw notYet("extractDatabaseIdentifiers");
    }

    @Override
    public List findForMassDelete(Operation op, boolean forceImplicitJoin) {
        throw notYet("findForMassDelete");
    }

    @Override
    public void deleteUsingOperation(Operation op) {
        throw notYet("deleteUsingOperation");
    }

    @Override
    public int deleteBatchUsingOperation(Operation op, int batchSize) {
        throw notYet("deleteBatchUsingOperation");
    }

    @Override
    public void batchUpdate(BatchUpdateOperation op) {
        throw notYet("batchUpdate");
    }

    @Override
    public void multiUpdate(MultiUpdateOperation op) {
        throw notYet("multiUpdate");
    }

    @Override
    public void prepareForMassDelete(Operation op, boolean forceImplicitJoin) {
        throw notYet("prepareForMassDelete");
    }

    @Override
    public void prepareForMassPurge(Operation op, boolean forceImplicitJoin) {
        throw notYet("prepareForMassPurge");
    }

    @Override
    public void prepareForMassPurge(List dataObjects) {
        throw notYet("prepareForMassPurge");
    }

    @Override
    public void setTxParticipationMode(TxParticipationMode mode, MithraTransaction tx) {
        if (mode == null) {
            // Existing callers pass null; that is not a request for pessimistic locks.
            return;
        }
        throw new DynamoDbTransactionException("RELADYNAMO-TXN-007",
                "unsupported TxParticipationMode " + mode.getClass().getSimpleName()
                        + "; database pessimistic locks and optimistic version checks are not implemented");
    }

    @Override
    public List getForDateRange(MithraDataObject data, Timestamp start, Timestamp end) {
        throw notYet("getForDateRange");
    }

    @Override
    public MithraDataObject enrollDatedObject(MithraDatedTransactionalObject object) {
        // Cache-resident enrollment: Reladomo already has the committed image on the object.
        // A database round-trip here would either miss staged writes (RELADYNAMO-TXN-006)
        // or re-read committed state the cache already holds. getForDateRange remains the
        // unimplemented path for objects that are not in cache.
        writer.beforeRead();
        if (object == null) {
            throw new IllegalArgumentException("cannot enroll a null " + mapping.className());
        }
        MithraDataObject data = object.zGetCurrentData();
        if (data == null) {
            throw new IllegalArgumentException(
                    "enrollDatedObject of " + mapping.className() + " has no current data");
        }
        return data;
    }

    // --- helpers ---------------------------------------------------------------------

    private Map<String, Object> rowOf(MithraDataObject data) {
        return MithraDataAccessor.extract(finder, data);
    }

    private List<Map<String, Object>> rowsOf(List dataObjects) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (dataObjects == null) {
            return rows;
        }
        for (Object o : dataObjects) {
            rows.add(rowOf(dataOf(o)));
        }
        return rows;
    }

    /**
     * Reladomo does not always hand the persister a bare {@link MithraDataObject}. On the dated
     * write path the elements are {@code InTransactionDatedTransactionalObject} wrappers, and casting
     * them directly produces a {@code ClassCastException} that names neither the cause nor the
     * remedy — which is how this was originally found.
     */
    private MithraDataObject dataOf(Object o) {
        if (o instanceof MithraDataObject) {
            return (MithraDataObject) o;
        }
        if (o instanceof InTransactionDatedTransactionalObject) {
            // The wrapper carries both the committed data and the in-transaction data.
            // zGetCurrentData() can be the not-yet-populated one — it returned a row with a null
            // primary key. zGetTxDataForRead() is the version the transaction is actually writing.
            InTransactionDatedTransactionalObject tx = (InTransactionDatedTransactionalObject) o;
            MithraDataObject data = tx.zGetTxDataForRead();
            return data != null ? data : tx.zGetCurrentData();
        }
        if (o instanceof MithraDatedTransactionalObject) {
            return ((MithraDatedTransactionalObject) o).zGetCurrentData();
        }
        if (o instanceof MithraTransactionalObject) {
            // Non-dated batch insert hands us the transactional object, not a data object
            // (BatchInsertOperation.objects is FastList<MithraTransactionalObject>).
            // zGetCurrentData() is the committed/empty side — ResultLabel/Car.batchInsert arrived
            // with a null PK. zGetTxDataForRead() is the in-transaction payload (javap 18.1.0).
            MithraTransactionalObject txObj = (MithraTransactionalObject) o;
            MithraDataObject data = txObj.zGetTxDataForRead();
            return data != null ? data : txObj.zGetCurrentData();
        }
        throw new IllegalArgumentException(
                "cannot extract data from " + o.getClass().getName() + " for " + mapping.className()
                        + ". Add the case here rather than letting a ClassCastException escape — the "
                        + "cast failure names neither the cause nor the remedy.");
    }

    /**
     * Named, not generic. An adapter that returned an empty result for an unimplemented read would
     * look like a working query over an empty table — far harder to diagnose than a refusal.
     */
    private void requireReadPath(String method) {
        if (planner == null || executor == null || design == null || plannerConfig == null) {
            throw new UnsupportedOperationException(
                    "DynamoDbPersister." + method + " needs the read path for "
                            + mapping.className() + ": construct with a QueryPlanner, "
                            + "QueryPlanExecutor, PhysicalDesign and PlannerConfig. "
                            + "This instance was built write-only.");
        }
    }

    private UnsupportedOperationException notYet(String method) {
        return new UnsupportedOperationException(
                "DynamoDbPersister." + method + " is not implemented yet for "
                        + mapping.className() + ". The read path lands with QueryPlanExecutor; "
                        + "failing loudly rather than returning an empty result, which would be "
                        + "indistinguishable from a legitimately empty table.");
    }
}
