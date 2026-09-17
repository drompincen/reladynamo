package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inspection acceptance case 3: a failed, already-flushed Reladomo transaction leaves no
 * partial durable result. R-01 buffers physical writes and commits through TransactWriteItems
 * only when the Reladomo transaction commits — flush is not a DynamoDB commit.
 */
class AcceptanceFailedTransactionTest {

    private static final int ID_MULTI_A = 96001;
    private static final int ID_MULTI_B = 96002;
    private static final int ID_MULTI_C = 96003;
    private static final int ID_BETWEEN = 96004;

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static ItemCodec codec;
    private static DynamoDbWriter writer;
    private static DynamoDbPersister adapter;
    private static MithraObjectReader jdbcReader;
    private static MithraAbstractObjectPortal portal;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        mapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffBalance.xml"));
        codec = new ItemCodec(mapping);
        DifferentialSupport.createPkSkTable(ddb, mapping.tableName());
        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        adapter = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping, writer,
                new QueryPlanner(), new QueryPlanExecutor(ddb.client(), codec),
                PhysicalDesign.builder(mapping)
                        .infinityFrom(DiffBalanceFinder.getFinderInstance()).build(),
                PlannerConfig.builder().build());
        portal = (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();
        jdbcReader = portal.getDatabaseObject();
    }

    @AfterAll
    static void tearDown() {
        if (portal != null && jdbcReader != null) {
            portal.setMithraObjectReader(jdbcReader);
        }
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void flushed_multi_insert_then_throw_leaves_nothing_durable() {
        portal.setMithraObjectReader(adapter);
        try {
            assertThat(rowsFor(ID_MULTI_A, ID_MULTI_B, ID_MULTI_C))
                    .as("precondition: the aborted ids must not already exist")
                    .isEmpty();
            assertThatThrownBy(() -> MithraManagerProvider.getMithraManager()
                    .executeTransactionalCommand(tx -> {
                        insertBalance(ID_MULTI_A, 1.0, "a");
                        insertBalance(ID_MULTI_B, 2.0, "b");
                        insertBalance(ID_MULTI_C, 3.0, "c");
                        tx.executeBufferedOperations();
                        throw new IllegalStateException("abort after flush of three inserts");
                    })).hasMessageContaining("abort after flush of three inserts");

            List<Map<String, Object>> leftover = rowsFor(ID_MULTI_A, ID_MULTI_B, ID_MULTI_C);
            assertThat(leftover)
                    .as("nothing durable — not fewer rows, not the last one missing. leftover=%s",
                            leftover)
                    .isEmpty();
        } finally {
            portal.setMithraObjectReader(jdbcReader);
            portal.clearQueryCache();
            portal.getCache().clear();
        }
    }

    @Test
    void failing_between_close_and_replacement_keeps_the_old_rectangle_open() {
        portal.setMithraObjectReader(adapter);
        try {
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                tx.setProcessingStartTime(DifferentialSupport.utc(2026, 2, 1).getTime());
                insertBalance(ID_BETWEEN, 10.0, "open");
                return null;
            });
            List<Map<String, Object>> before = rowsFor(ID_BETWEEN);
            assertThat(before).hasSize(1);
            Map<String, Object> open = before.get(0);
            assertThat(open.get("processingDateTo"))
                    .as("seed rectangle must be processing-open")
                    .isEqualTo(DifferentialSupport.infinity());

            Map<String, Object> closed = copy(open);
            closed.put("processingDateTo", DifferentialSupport.utc(2026, 3, 1));

            assertThatThrownBy(() -> MithraManagerProvider.getMithraManager()
                    .executeTransactionalCommand(tx -> {
                        writer.update(closed, open);
                        tx.executeBufferedOperations();
                        throw new IllegalStateException("before replacement insert");
                    })).hasMessageContaining("before replacement insert");

            List<Map<String, Object>> after = rowsFor(ID_BETWEEN);
            assertThat(after)
                    .as("abort between close and replacement must not add a replacement row. after=%s",
                            after)
                    .hasSize(1);
            assertThat(after.get(0).get("quantity")).isEqualTo(Double.valueOf(10.0));
            assertThat(after.get(0).get("label")).isEqualTo("open");
            assertThat(after.get(0).get("processingDateTo"))
                    .as("the old rectangle must still be open; a durable close without a replacement "
                            + "is the bitemporal hole this case exists to catch")
                    .isEqualTo(DifferentialSupport.infinity());
        } finally {
            portal.setMithraObjectReader(jdbcReader);
            portal.clearQueryCache();
            portal.getCache().clear();
        }
    }

    private static void insertBalance(int id, double quantity, String label) {
        DiffBalance b = new DiffBalance(DifferentialSupport.utc(2026, 1, 1));
        b.setBalanceId(id);
        b.setQuantity(quantity);
        b.setLabel(label);
        b.insert();
    }

    private static List<Map<String, Object>> rowsFor(int... ids) {
        ScanResponse scan = ddb.client().scan(b -> b.tableName(mapping.tableName()));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, AttributeValue> item : scan.items()) {
            if (item.get("pk") == null || item.get("pk").s() == null) {
                continue;
            }
            String pk = item.get("pk").s();
            for (int id : ids) {
                if (pk.endsWith("#" + id)) {
                    out.add(codec.decode(item));
                    break;
                }
            }
        }
        return out;
    }

    private static Map<String, Object> copy(Map<String, Object> row) {
        return new LinkedHashMap<String, Object>(row);
    }
}
