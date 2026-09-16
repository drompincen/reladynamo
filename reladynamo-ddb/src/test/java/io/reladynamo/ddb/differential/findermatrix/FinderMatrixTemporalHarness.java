package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.MithraList;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.core.plan.ReladynamoScanRequiredException;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.DiffH2ConnectionManager;
import io.reladynamo.ddb.differential.DifferentialSupport;
import io.reladynamo.ddb.differential.domain.DiffAuditFinder;
import io.reladynamo.ddb.differential.domain.DiffAuditList;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * §2 cold-cache paired runner for dated fixtures (DiffBalance, DiffAudit). Same idiom as
 * {@link FinderMatrixHarness}: H2 is the oracle, DDB rows are codec-written, MATCH requires
 * a strictly positive data-read delta.
 */
final class FinderMatrixTemporalHarness implements AutoCloseable {

    enum Entity { BALANCE, AUDIT }

    final LocalDynamoDb ddb;
    final RequestCounters counters;
    final SqlReadProbe sql;

    private final BoundBalance balance;
    private final BoundAudit audit;

    private FinderMatrixTemporalHarness(LocalDynamoDb ddb, RequestCounters counters, SqlReadProbe sql,
                                        BoundBalance balance, BoundAudit audit) {
        this.ddb = ddb;
        this.counters = counters;
        this.sql = sql;
        this.balance = balance;
        this.audit = audit;
    }

    static FinderMatrixTemporalHarness boot() {
        DifferentialSupport.boot();
        Timestamp bizInf = DiffBalanceFinder.businessDate().getInfinityDate();
        Timestamp procInf = DiffBalanceFinder.processingDate().getInfinityDate();
        if (bizInf.getTime() != procInf.getTime()) {
            throw new IllegalStateException("DiffBalance axes disagree on infinity: business="
                    + bizInf + " processing=" + procInf);
        }
        LocalDynamoDb ddb = LocalDynamoDb.start();
        SqlReadProbe sql = new SqlReadProbe();
        DiffH2ConnectionManager.installSqlProbe(sql);

        RequestCounters counters = new RequestCounters();
        DynamoDbClient counting = CountingDynamoDb.wrap(ddb.client(), counters);

        BoundBalance balance = BoundBalance.create(ddb, counting);
        BoundAudit audit = BoundAudit.create(ddb, counting);
        return new FinderMatrixTemporalHarness(ddb, counters, sql, balance, audit);
    }

    @Override
    public void close() {
        try {
            balance.portal.setMithraObjectReader(balance.jdbcReader);
            audit.portal.setMithraObjectReader(audit.jdbcReader);
            clearCold();
        } finally {
            DiffH2ConnectionManager.installSqlProbe(null);
            ddb.close();
        }
    }

    void truncateH2() {
        try (Connection c = DiffH2ConnectionManager.getInstance().getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("DELETE FROM DIFF_BALANCE");
            s.execute("DELETE FROM DIFF_AUDIT");
        } catch (SQLException e) {
            throw new IllegalStateException("H2 truncate failed", e);
        }
    }

    void seedH2Balances(List<TemporalFixtureManifests.BalanceRow> rows) {
        String sqlText = "INSERT INTO DIFF_BALANCE (BALANCE_ID, QUANTITY, LABEL, NOTE, "
                + "FROM_Z, THRU_Z, IN_Z, OUT_Z) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = DiffH2ConnectionManager.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(sqlText)) {
            for (int i = 0; i < rows.size(); i++) {
                TemporalFixtureManifests.BalanceRow row = rows.get(i);
                ps.setInt(1, row.balanceId);
                ps.setDouble(2, row.quantity);
                ps.setString(3, row.label);
                if (row.note == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, row.note);
                }
                // Generated DatabaseObject reads as-of columns with MithraTimestamp.DefaultTimeZone
                // (JVM zone), not the connection-manager UTC calendar. Insert the same way.
                ps.setTimestamp(5, row.businessFrom);
                ps.setTimestamp(6, row.businessTo);
                ps.setTimestamp(7, row.processingFrom);
                ps.setTimestamp(8, row.processingTo);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("H2 DiffBalance seed failed", e);
        }
    }

    void seedH2Audits(List<TemporalFixtureManifests.AuditRow> rows) {
        String sqlText = "INSERT INTO DIFF_AUDIT (AUDIT_ID, QUANTITY, LABEL, NOTE, IN_Z, OUT_Z) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection c = DiffH2ConnectionManager.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(sqlText)) {
            for (int i = 0; i < rows.size(); i++) {
                TemporalFixtureManifests.AuditRow row = rows.get(i);
                ps.setInt(1, row.auditId);
                ps.setDouble(2, row.quantity);
                ps.setString(3, row.label);
                if (row.note == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, row.note);
                }
                ps.setTimestamp(5, row.processingFrom);
                ps.setTimestamp(6, row.processingTo);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("H2 DiffAudit seed failed", e);
        }
    }

    void seedDdbBalances(List<TemporalFixtureManifests.BalanceRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            balance.writer.insert(rows.get(i).toMap());
        }
    }

    void seedDdbAudits(List<TemporalFixtureManifests.AuditRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            audit.writer.insert(rows.get(i).toMap());
        }
    }

    void clearCold() {
        balance.portal.getCache().clear();
        balance.portal.clearQueryCache();
        audit.portal.getCache().clear();
        audit.portal.clearQueryCache();
    }

    InvocationResult run(FinderMatrixHarness.Backend backend, Entity entity,
                         FinderMatrixHarness.OperationRecipe recipe, FinderShape shape) {
        return run(backend, entity, recipe, shape, true);
    }

    InvocationResult run(FinderMatrixHarness.Backend backend, Entity entity,
                         FinderMatrixHarness.OperationRecipe recipe, FinderShape shape,
                         boolean applyProtocol) {
        Operation op = recipe.create();
        if (applyProtocol) {
            clearCold();
        }
        Bound bound = bound(entity);
        if (backend == FinderMatrixHarness.Backend.DDB) {
            sql.reset();
            sql.forbid();
            counters.reset();
            bound.recording.reset();
            bound.portal.setMithraObjectReader(bound.recording.proxy());
        } else {
            sql.allow();
            sql.reset();
            bound.portal.setMithraObjectReader(bound.jdbcReader);
        }
        try {
            InvocationResult result = invoke(entity, bound, op, shape,
                    applyProtocol && backend == FinderMatrixHarness.Backend.DDB);
            if (backend == FinderMatrixHarness.Backend.DDB) {
                RequestAssertions.requireNoSqlDuringDdb(sql.selects(), shape.toString());
                RequestAssertions.requirePositiveDataReads(result.counters.dataReads(), shape.toString());
                RequestAssertions.requireZeroScan(result.counters.scan.get(), shape.toString());
                RequestAssertions.requireReaderEntered(result.readerEntries, shape.toString());
                assertRoute(shape, result);
            } else {
                RequestAssertions.requireH2JdbcRead(sql.selects(), shape.toString());
            }
            return result;
        } catch (ReladynamoScanRequiredException e) {
            throw new AssertionError("unexpected REFUSE during MATCH_H2 " + shape + ": " + e.getMessage(), e);
        } finally {
            bound.portal.setMithraObjectReader(bound.jdbcReader);
            sql.allow();
            if (applyProtocol) {
                clearCold();
            }
        }
    }

    void matchH2(Entity entity, FinderMatrixHarness.OperationRecipe recipe, FinderShape shape) {
        InvocationResult h2 = run(FinderMatrixHarness.Backend.H2, entity, recipe, shape, true);
        InvocationResult ddb = run(FinderMatrixHarness.Backend.DDB, entity, recipe, shape, true);
        if (shape.kind == FinderShape.Kind.COUNT) {
            if (!h2.count.equals(ddb.count)) {
                throw new AssertionError("count mismatch H2=" + h2.count + " DDB=" + ddb.count
                        + " " + shape);
            }
            return;
        }
        if (shape.ordered) {
            if (!h2.rows.equals(ddb.rows)) {
                throw new AssertionError("ordered sequence mismatch\nH2=" + h2.rows
                        + "\nDDB=" + ddb.rows);
            }
        } else if (!FinderMatrixHarness.sameMultiset(h2.rows, ddb.rows)) {
            throw new AssertionError("unordered multiset mismatch\nH2=" + h2.rows
                    + "\nDDB=" + ddb.rows);
        }
        assertNoDuplicateDatedIdentity(entity, ddb.rows);
    }

    private InvocationResult invoke(Entity entity, Bound bound, Operation op, FinderShape shape,
                                    boolean bypass) {
        switch (shape.kind) {
            case FIND_MANY:
                return invokeFindMany(entity, bound, op, shape, bypass);
            case FIND_ONE:
                return snapshotOne(entity, bound, findOne(entity, op));
            case FIND_ONE_BYPASS:
                return snapshotOne(entity, bound, findOneBypass(entity, op));
            case COUNT:
                int n = countUnresolved(entity, op);
                return InvocationResult.countOnly(n, counters.copy(), bound.executor.lastExplain(),
                        bound.recording.readerEntries(), sql.selects());
            default:
                throw new IllegalStateException("unknown shape " + shape.kind);
        }
    }

    private InvocationResult invokeFindMany(Entity entity, Bound bound, Operation op,
                                            FinderShape shape, boolean bypass) {
        MithraList list = findMany(entity, op);
        if (bypass) {
            list.setBypassCache(true);
        }
        if (shape.orderBy != null) {
            if (entity == Entity.BALANCE) {
                ((DiffBalanceList) list).setOrderBy(shape.orderBy);
            } else {
                ((DiffAuditList) list).setOrderBy(shape.orderBy);
            }
        }
        if (shape.max != null) {
            if (entity == Entity.BALANCE) {
                ((DiffBalanceList) list).setMaxObjectsToRetrieve(shape.max.intValue());
            } else {
                ((DiffAuditList) list).setMaxObjectsToRetrieve(shape.max.intValue());
            }
        }
        list.forceResolve();
        return snapshotList(entity, bound, list);
    }

    private InvocationResult snapshotList(Entity entity, Bound bound, MithraList list) {
        RelatedFinder finder = finderOf(entity);
        List<TypedSnapshot> rows = new ArrayList<TypedSnapshot>();
        for (int i = 0; i < list.size(); i++) {
            rows.add(TypedSnapshot.ofObject(finder, list.get(i)));
        }
        return InvocationResult.rows(rows, counters.copy(), bound.executor.lastExplain(),
                bound.recording.readerEntries(), sql.selects());
    }

    private InvocationResult snapshotOne(Entity entity, Bound bound, Object one) {
        List<TypedSnapshot> rows = new ArrayList<TypedSnapshot>();
        if (one != null) {
            rows.add(TypedSnapshot.ofObject(finderOf(entity), one));
        }
        return InvocationResult.rows(rows, counters.copy(), bound.executor.lastExplain(),
                bound.recording.readerEntries(), sql.selects());
    }

    private static Object findOne(Entity entity, Operation op) {
        if (entity == Entity.BALANCE) {
            return DiffBalanceFinder.findOne(op);
        }
        return DiffAuditFinder.findOne(op);
    }

    private static Object findOneBypass(Entity entity, Operation op) {
        if (entity == Entity.BALANCE) {
            return DiffBalanceFinder.findOneBypassCache(op);
        }
        return DiffAuditFinder.findOneBypassCache(op);
    }

    private static MithraList findMany(Entity entity, Operation op) {
        if (entity == Entity.BALANCE) {
            return DiffBalanceFinder.findMany(op);
        }
        return DiffAuditFinder.findMany(op);
    }

    private static int countUnresolved(Entity entity, Operation op) {
        if (entity == Entity.BALANCE) {
            return DiffBalanceFinder.findMany(op).count();
        }
        return DiffAuditFinder.findMany(op).count();
    }

    private static RelatedFinder finderOf(Entity entity) {
        if (entity == Entity.BALANCE) {
            return DiffBalanceFinder.getFinderInstance();
        }
        return DiffAuditFinder.getFinderInstance();
    }

    private Bound bound(Entity entity) {
        return entity == Entity.BALANCE ? balance : audit;
    }

    private void assertRoute(FinderShape shape, InvocationResult result) {
        RequestCounters c = result.counters;
        switch (shape.route) {
            case QUERY_GSI:
            case QUERY_BASE:
                if (c.query.get() < 1) {
                    throw new AssertionError("expected Query but " + c.describe());
                }
                break;
            case GET_ITEM:
                if (c.getItem.get() < 1) {
                    throw new AssertionError("expected GetItem but " + c.describe());
                }
                break;
            case EXECUTE_OR_FANOUT:
                if (c.executeStatement.get() < 1 && c.query.get() < 1 && c.getItem.get() < 1) {
                    throw new AssertionError("expected ExecuteStatement or per-key fan-out but "
                            + c.describe());
                }
                break;
            default:
                throw new IllegalStateException("unknown route " + shape.route);
        }
    }

    static void assertNoDuplicateDatedIdentity(Entity entity, List<TypedSnapshot> rows) {
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            String id = datedIdentity(entity, rows.get(i));
            if (seen.contains(id)) {
                throw new AssertionError("duplicate physical identity " + id + " in " + rows);
            }
            seen.add(id);
        }
    }

    static String datedIdentity(Entity entity, TypedSnapshot row) {
        if (entity == Entity.BALANCE) {
            return row.get("balanceId") + "#" + ts(row.get("businessDateFrom"))
                    + "#" + ts(row.get("processingDateFrom"));
        }
        return row.get("auditId") + "#" + ts(row.get("processingDateFrom"));
    }

    private static String ts(Object v) {
        if (v instanceof Timestamp) {
            Timestamp t = (Timestamp) v;
            return t.getTime() + "ns=" + t.getNanos();
        }
        return String.valueOf(v);
    }

    static String labelOf(TypedSnapshot row) {
        Object v = row.get("label");
        return v == null ? null : String.valueOf(v);
    }

    private static class Bound {
        final MithraAbstractObjectPortal portal;
        final MithraObjectReader jdbcReader;
        final DynamoDbWriter writer;
        final QueryPlanExecutor executor;
        final RecordingPersister recording;

        Bound(MithraAbstractObjectPortal portal, MithraObjectReader jdbcReader,
              DynamoDbWriter writer, QueryPlanExecutor executor, RecordingPersister recording) {
            this.portal = portal;
            this.jdbcReader = jdbcReader;
            this.writer = writer;
            this.executor = executor;
            this.recording = recording;
        }
    }

    private static final class BoundBalance extends Bound {
        BoundBalance(MithraAbstractObjectPortal portal, MithraObjectReader jdbcReader,
                     DynamoDbWriter writer, QueryPlanExecutor executor, RecordingPersister recording) {
            super(portal, jdbcReader, writer, executor, recording);
        }

        static BoundBalance create(LocalDynamoDb ddb, DynamoDbClient counting) {
            EntityMapping mapping = new MithraObjectXmlParser().parse(
                    DifferentialSupport.loadResource("/reladomo/models/DiffBalance.xml"));
            ItemCodec codec = new ItemCodec(mapping);
            PhysicalDesign design = PhysicalDesign.builder(mapping)
                    .infinityFrom(DiffBalanceFinder.getFinderInstance())
                    .build();
            new TableCreator(ddb.client()).create(design);
            DynamoDbWriter writer = new DynamoDbWriter(
                    ddb.client(), mapping, codec, new DefaultKeyStrategy());
            QueryPlanExecutor executor = new QueryPlanExecutor(counting, codec);
            DynamoDbPersister adapter = new DynamoDbPersister(
                    DiffBalanceFinder.getFinderInstance(), mapping, writer,
                    new QueryPlanner(), executor, design, PlannerConfig.builder().build());
            RecordingPersister recording = new RecordingPersister(adapter);
            MithraAbstractObjectPortal portal =
                    (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();
            MithraObjectReader jdbcReader = portal.getMithraObjectReader();
            return new BoundBalance(portal, jdbcReader, writer, executor, recording);
        }
    }

    private static final class BoundAudit extends Bound {
        BoundAudit(MithraAbstractObjectPortal portal, MithraObjectReader jdbcReader,
                   DynamoDbWriter writer, QueryPlanExecutor executor, RecordingPersister recording) {
            super(portal, jdbcReader, writer, executor, recording);
        }

        static BoundAudit create(LocalDynamoDb ddb, DynamoDbClient counting) {
            EntityMapping mapping = new MithraObjectXmlParser().parse(
                    DifferentialSupport.loadResource("/reladomo/models/DiffAudit.xml"));
            ItemCodec codec = new ItemCodec(mapping);
            PhysicalDesign design = PhysicalDesign.builder(mapping)
                    .infinityFrom(DiffAuditFinder.getFinderInstance())
                    .build();
            new TableCreator(ddb.client()).create(design);
            DynamoDbWriter writer = new DynamoDbWriter(
                    ddb.client(), mapping, codec, new DefaultKeyStrategy());
            QueryPlanExecutor executor = new QueryPlanExecutor(counting, codec);
            DynamoDbPersister adapter = new DynamoDbPersister(
                    DiffAuditFinder.getFinderInstance(), mapping, writer,
                    new QueryPlanner(), executor, design, PlannerConfig.builder().build());
            RecordingPersister recording = new RecordingPersister(adapter);
            MithraAbstractObjectPortal portal =
                    (MithraAbstractObjectPortal) DiffAuditFinder.getMithraObjectPortal();
            MithraObjectReader jdbcReader = portal.getMithraObjectReader();
            return new BoundAudit(portal, jdbcReader, writer, executor, recording);
        }
    }
}
