package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.MithraList;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.OrderByTranslator;
import io.reladynamo.core.plan.PartitionKeyEncoder;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.core.plan.ReladynamoScanRequiredException;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.DiffH2ConnectionManager;
import io.reladynamo.ddb.differential.DifferentialSupport;
import io.reladynamo.ddb.differential.domain.DiffFinderValue;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import io.reladynamo.ddb.differential.domain.DiffFinderValueList;
import io.reladynamo.ddb.exec.ExecutionExplain;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §2 cold-cache paired runner. H2 is the oracle. DDB data is codec-written. Every MATCH
 * requires a strictly positive DDB data-read delta — absence of an exception is not proof.
 */
final class FinderMatrixHarness implements AutoCloseable {

    static final String GSI_NAME = "gsi_bucketId";
    static final String GSI_ATTR = "bucketId";

    enum Backend { H2, DDB }

    interface OperationRecipe {
        Operation create();
    }

    final LocalDynamoDb ddb;
    final RequestCounters counters;
    final SqlReadProbe sql;
    final EntityMapping mapping;
    final ItemCodec codec;
    final DynamoDbWriter writer;
    final QueryPlanExecutor executor;
    final RecordingPersister recording;
    final DynamoDbPersister adapter;
    final PhysicalDesign design;
    final MithraAbstractObjectPortal portal;
    final MithraObjectReader jdbcReader;
    final DefaultKeyStrategy keys = new DefaultKeyStrategy();
    final DynamoDbClient rawClient;

    private FinderMatrixHarness(LocalDynamoDb ddb, RequestCounters counters, SqlReadProbe sql,
                                EntityMapping mapping, ItemCodec codec, DynamoDbWriter writer,
                                QueryPlanExecutor executor, RecordingPersister recording,
                                DynamoDbPersister adapter, PhysicalDesign design,
                                MithraAbstractObjectPortal portal, MithraObjectReader jdbcReader,
                                DynamoDbClient rawClient) {
        this.ddb = ddb;
        this.counters = counters;
        this.sql = sql;
        this.mapping = mapping;
        this.codec = codec;
        this.writer = writer;
        this.executor = executor;
        this.recording = recording;
        this.adapter = adapter;
        this.design = design;
        this.portal = portal;
        this.jdbcReader = jdbcReader;
        this.rawClient = rawClient;
    }

    static FinderMatrixHarness boot() {
        DifferentialSupport.boot();
        LocalDynamoDb ddb = LocalDynamoDb.start();
        SqlReadProbe sql = new SqlReadProbe();
        DiffH2ConnectionManager.installSqlProbe(sql);

        EntityMapping mapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffFinderValue.xml"));
        ItemCodec codec = new ItemCodec(mapping);
        GsiSpec gsi = GsiSpec.foreignKey(GSI_NAME, GSI_ATTR);
        PhysicalDesign design = PhysicalDesign.builder(mapping).addGsi(gsi).build();
        new TableCreator(ddb.client()).create(design);

        RequestCounters counters = new RequestCounters();
        DynamoDbClient counting = CountingDynamoDb.wrap(ddb.client(), counters);
        DynamoDbWriter writer = new DynamoDbWriter(
                ddb.client(), mapping, codec, new DefaultKeyStrategy(), design.gsis());
        QueryPlanExecutor executor = new QueryPlanExecutor(counting, codec);
        PlannerConfig config = PlannerConfig.builder().build();
        DynamoDbPersister adapter = new DynamoDbPersister(
                DiffFinderValueFinder.getFinderInstance(), mapping, writer,
                new QueryPlanner(), executor, design, config);
        RecordingPersister recording = new RecordingPersister(adapter);

        MithraAbstractObjectPortal portal =
                (MithraAbstractObjectPortal) DiffFinderValueFinder.getMithraObjectPortal();
        MithraObjectReader jdbcReader = portal.getMithraObjectReader();
        return new FinderMatrixHarness(ddb, counters, sql, mapping, codec, writer, executor,
                recording, adapter, design, portal, jdbcReader, ddb.client());
    }

    @Override
    public void close() {
        try {
            portal.setMithraObjectReader(jdbcReader);
            clearCold();
        } finally {
            DiffH2ConnectionManager.installSqlProbe(null);
            ddb.close();
        }
    }

    void truncateH2() {
        try (java.sql.Connection c = DiffH2ConnectionManager.getInstance().getConnection();
             java.sql.Statement s = c.createStatement()) {
            s.execute("DELETE FROM DIFF_FINDER_VALUE");
        } catch (SQLException e) {
            throw new IllegalStateException("H2 truncate failed", e);
        }
    }

    void seedH2(List<ValueRow> rows) {
        String sqlText = "INSERT INTO DIFF_FINDER_VALUE ("
                + "SCOPE_ID, ROW_ID, BUCKET_ID, INT_VALUE, LONG_VALUE, DOUBLE_VALUE, FLOAT_VALUE, "
                + "DECIMAL_VALUE, TEXT_VALUE, BOOLEAN_VALUE, TIMESTAMP_VALUE, BYTES_VALUE) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = DiffH2ConnectionManager.getInstance().getConnection();
             PreparedStatement ps = c.prepareStatement(sqlText)) {
            for (int i = 0; i < rows.size(); i++) {
                bind(ps, rows.get(i));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("H2 seed failed", e);
        }
    }

    void seedDdb(List<ValueRow> rows) {
        List<Map<String, Object>> maps = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rows.size(); i++) {
            maps.add(rows.get(i).toMap());
        }
        writer.batchInsert(maps);
    }

    void awaitGsi(int bucketId, List<ValueRow> expected) {
        String encoded = PartitionKeyEncoder.gsiPartitionKey(GSI_ATTR, Integer.valueOf(bucketId));
        long deadline = System.currentTimeMillis() + 5000L;
        IllegalStateException last = new IllegalStateException("GSI never converged");
        while (System.currentTimeMillis() < deadline) {
            try {
                List<TypedSnapshot> actual = readGsi(encoded);
                if (sameMultiset(snapshotsOf(expected), actual)) {
                    clearCold();
                    counters.reset();
                    recording.reset();
                    sql.reset();
                    return;
                }
                last = new IllegalStateException("GSI mismatch: expected " + expected.size()
                        + " got " + actual.size() + " " + actual);
            } catch (RuntimeException e) {
                last = new IllegalStateException("GSI poll failed", e);
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for GSI", e);
            }
        }
        throw last;
    }

    Map<String, AttributeValue> getItemRaw(ValueRow row) {
        Map<String, AttributeValue> key = new LinkedHashMap<String, AttributeValue>();
        key.put("pk", AttributeValue.builder().s(keys.partitionKey(mapping, row.toMap())).build());
        key.put("sk", AttributeValue.builder().s(keys.sortKey(mapping, null, null)).build());
        GetItemResponse response = rawClient.getItem(b -> b
                .tableName(mapping.tableName())
                .key(key)
                .consistentRead(Boolean.TRUE));
        if (!response.hasItem()) {
            return Collections.emptyMap();
        }
        return response.item();
    }

    void clearCold() {
        portal.getCache().clear();
        portal.clearQueryCache();
    }

    /**
     * Run one backend. {@code applyProtocol=false} is the sensitivity path: caches are left
     * warm and bypass is not set, so a second call is served from Reladomo's cache.
     */
    InvocationResult run(Backend backend, OperationRecipe recipe, FinderShape shape,
                         boolean applyProtocol) {
        Operation op = recipe.create();
        if (applyProtocol) {
            clearCold();
        }
        if (backend == Backend.DDB) {
            sql.reset();
            sql.forbid();
            counters.reset();
            recording.reset();
            portal.setMithraObjectReader(recording.proxy());
        } else {
            sql.allow();
            sql.reset();
            portal.setMithraObjectReader(jdbcReader);
        }
        try {
            InvocationResult result = invoke(op, shape, applyProtocol && backend == Backend.DDB);
            if (backend == Backend.DDB) {
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
            portal.setMithraObjectReader(jdbcReader);
            sql.allow();
            if (applyProtocol) {
                clearCold();
            }
        }
    }

    InvocationResult run(Backend backend, OperationRecipe recipe, FinderShape shape) {
        return run(backend, recipe, shape, true);
    }

    void matchH2(OperationRecipe recipe, FinderShape shape) {
        InvocationResult h2 = run(Backend.H2, recipe, shape, true);
        InvocationResult ddb = run(Backend.DDB, recipe, shape, true);
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
        } else if (!sameMultiset(h2.rows, ddb.rows)) {
            throw new AssertionError("unordered multiset mismatch\nH2=" + h2.rows
                    + "\nDDB=" + ddb.rows);
        }
        assertNoDuplicateIdentity(ddb.rows);
    }

    RelatedFinder finder() {
        return DiffFinderValueFinder.getFinderInstance();
    }

    private InvocationResult invoke(Operation op, FinderShape shape, boolean bypass) {
        switch (shape.kind) {
            case FIND_MANY:
                return invokeFindMany(op, shape, bypass);
            case FIND_ONE:
                return snapshotOne(DiffFinderValueFinder.findOne(op));
            case FIND_ONE_BYPASS:
                return snapshotOne(DiffFinderValueFinder.findOneBypassCache(op));
            case COUNT:
                DiffFinderValueList unresolved = DiffFinderValueFinder.findMany(op);
                int n = unresolved.count();
                return InvocationResult.countOnly(n, counters.copy(), executor.lastExplain(),
                        recording.readerEntries(), sql.selects());
            default:
                throw new IllegalStateException("unknown shape " + shape.kind);
        }
    }

    private InvocationResult invokeFindMany(Operation op, FinderShape shape, boolean bypass) {
        DiffFinderValueList list = DiffFinderValueFinder.findMany(op);
        if (bypass) {
            list.setBypassCache(true);
        }
        if (shape.orderBy != null) {
            // Reladomo 18.1.0 ByteArrayOrderBy crashes on empty/prefix arrays and
            // compares signed bytes. Replace with unsigned content order so H2
            // MATCH can serve as the oracle (finding 30).
            list.setOrderBy(OrderByTranslator.withUnsignedByteArrayOrder(shape.orderBy));
        }
        if (shape.max != null) {
            list.setMaxObjectsToRetrieve(shape.max.intValue());
        }
        list.forceResolve();
        return snapshotList(list);
    }

    private InvocationResult snapshotList(MithraList list) {
        List<TypedSnapshot> rows = new ArrayList<TypedSnapshot>();
        for (int i = 0; i < list.size(); i++) {
            rows.add(TypedSnapshot.ofObject(finder(), list.get(i)));
        }
        return InvocationResult.rows(rows, counters.copy(), executor.lastExplain(),
                recording.readerEntries(), sql.selects());
    }

    private InvocationResult snapshotOne(DiffFinderValue one) {
        List<TypedSnapshot> rows = new ArrayList<TypedSnapshot>();
        if (one != null) {
            rows.add(TypedSnapshot.ofObject(finder(), one));
        }
        return InvocationResult.rows(rows, counters.copy(), executor.lastExplain(),
                recording.readerEntries(), sql.selects());
    }

    private void assertRoute(FinderShape shape, InvocationResult result) {
        RequestCounters c = result.counters;
        switch (shape.route) {
            case QUERY_GSI:
                if (c.query.get() < 1) {
                    throw new AssertionError("expected Query on " + GSI_NAME + " but " + c.describe());
                }
                if (result.explain != null && result.explain.indexName() != null
                        && !GSI_NAME.equals(result.explain.indexName())
                        && result.explain.kind() != PlanKind.QUERY_FAN_OUT) {
                    throw new AssertionError("expected index " + GSI_NAME
                            + " but explain index=" + result.explain.indexName()
                            + " kind=" + result.explain.kind());
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

    private List<TypedSnapshot> readGsi(String encodedPk) {
        List<TypedSnapshot> rows = new ArrayList<TypedSnapshot>();
        Map<String, AttributeValue> values = new HashMap<String, AttributeValue>();
        values.put(":pk", AttributeValue.builder().s(encodedPk).build());
        Map<String, AttributeValue> start = null;
        do {
            QueryRequest.Builder b = QueryRequest.builder()
                    .tableName(mapping.tableName())
                    .indexName(GSI_NAME)
                    .keyConditionExpression(GSI_NAME + " = :pk")
                    .expressionAttributeValues(values)
                    .consistentRead(Boolean.FALSE);
            if (start != null && !start.isEmpty()) {
                b.exclusiveStartKey(start);
            }
            QueryResponse response = rawClient.query(b.build());
            if (response.items() != null) {
                for (Map<String, AttributeValue> item : response.items()) {
                    rows.add(new TypedSnapshot(codec.decode(item)));
                }
            }
            start = response.lastEvaluatedKey();
        } while (start != null && !start.isEmpty());
        return rows;
    }

    static List<TypedSnapshot> snapshotsOf(List<ValueRow> rows) {
        List<TypedSnapshot> out = new ArrayList<TypedSnapshot>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(TypedSnapshot.ofManifest(rows.get(i)));
        }
        return out;
    }

    static boolean sameMultiset(List<TypedSnapshot> a, List<TypedSnapshot> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<TypedSnapshot> remaining = new ArrayList<TypedSnapshot>(b);
        for (int i = 0; i < a.size(); i++) {
            int idx = remaining.indexOf(a.get(i));
            if (idx < 0) {
                return false;
            }
            remaining.remove(idx);
        }
        return remaining.isEmpty();
    }

    static void assertNoDuplicateIdentity(List<TypedSnapshot> rows) {
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            TypedSnapshot row = rows.get(i);
            String id = row.scopeId() + "#" + row.rowId();
            if (seen.contains(id)) {
                throw new AssertionError("duplicate physical identity " + id + " in " + rows);
            }
            seen.add(id);
        }
    }

    private static void bind(PreparedStatement ps, ValueRow row) throws SQLException {
        ps.setInt(1, row.scopeId);
        ps.setInt(2, row.rowId);
        ps.setInt(3, row.bucketId);
        setInt(ps, 4, row.intValue);
        if (row.longValue == null) {
            ps.setNull(5, Types.BIGINT);
        } else {
            ps.setLong(5, row.longValue.longValue());
        }
        if (row.doubleValue == null) {
            ps.setNull(6, Types.DOUBLE);
        } else {
            ps.setDouble(6, row.doubleValue.doubleValue());
        }
        if (row.floatValue == null) {
            ps.setNull(7, Types.REAL);
        } else {
            ps.setFloat(7, row.floatValue.floatValue());
        }
        if (row.decimalValue == null) {
            ps.setNull(8, Types.DECIMAL);
        } else {
            ps.setBigDecimal(8, row.decimalValue);
        }
        if (row.textValue == null) {
            ps.setNull(9, Types.VARCHAR);
        } else {
            ps.setString(9, row.textValue);
        }
        if (row.booleanValue == null) {
            ps.setNull(10, Types.BOOLEAN);
        } else {
            ps.setBoolean(10, row.booleanValue.booleanValue());
        }
        if (row.timestampValue == null) {
            ps.setNull(11, Types.TIMESTAMP);
        } else {
            // TimestampAttribute reads with the JVM zone, unlike AsOfAttribute which uses
            // the connection-manager UTC calendar. Insert the same way Reladomo will read.
            ps.setTimestamp(11, row.timestampValue);
        }
        if (row.bytesValue == null) {
            ps.setNull(12, Types.VARBINARY);
        } else {
            ps.setBytes(12, row.bytesValue);
        }
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value.intValue());
        }
    }
}
