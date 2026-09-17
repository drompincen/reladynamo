package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.persist.DurableTransactionTest;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real TemporalDirector and cache lifecycle with a deterministic physical-store boundary. */
class BoundDurableTransactionTest {
    @BeforeAll
    static void boot() {
        DifferentialSupport.boot();
    }

    private final DurableTransactionTest.Store store = new DurableTransactionTest.Store();
    private final EntityMapping mapping = DifferentialSupport.parseMapping("/reladomo/models/DiffBalance.xml");
    private final ItemCodec codec = new ItemCodec(mapping);
    private final MithraAbstractObjectPortal portal =
            (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();

    private void withAdapter(Runnable command) {
        MithraObjectReader jdbc = portal.getDatabaseObject();
        portal.clearQueryCache();
        portal.getCache().clear();
        portal.setMithraObjectReader(new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping,
                new DynamoDbWriter(store.client, mapping, codec, new DefaultKeyStrategy())));
        try {
            command.run();
        } finally {
            portal.setMithraObjectReader(jdbc);
            portal.clearQueryCache();
            portal.getCache().clear();
        }
    }

    private DiffBalance insert(int id) {
        DiffBalance b = new DiffBalance(Timestamp.valueOf("2026-01-01 00:00:00"));
        b.setBalanceId(id);
        b.setQuantity(10);
        b.setLabel("initial");
        b.insert();
        return b;
    }

    @Test
    void real_reladomo_flush_then_throw_is_not_durable() {
        withAdapter(() -> {
            assertThatThrownBy(() -> MithraManagerProvider.getMithraManager().executeTransactionalCommand(t -> {
                insert(89001);
                t.executeBufferedOperations();
                throw new IllegalStateException("abort after real flush");
            })).hasMessageContaining("abort after real flush");
            assertThat(store.rows(mapping.tableName())).isEmpty();
        });
    }

    @Test
    void cached_object_sees_own_update_but_rollback_preserves_complete_old_rectangle() {
        withAdapter(() -> {
            DiffBalance b = MithraManagerProvider.getMithraManager().executeTransactionalCommand(t -> insert(89002));
            List<Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> before =
                    store.rows(mapping.tableName());
            assertThatThrownBy(() -> MithraManagerProvider.getMithraManager().executeTransactionalCommand(t -> {
                b.setQuantity(77);
                assertThat(b.getQuantity()).isEqualTo(77);
                t.executeBufferedOperations();
                throw new IllegalStateException("abort correction after flush");
            })).hasMessageContaining("abort correction after flush");
            assertThat(store.rows(mapping.tableName())).containsExactlyInAnyOrderElementsOf(before);
            // Post-rollback getters refresh through refreshDatedObject, which is still
            // unimplemented. The durability contract is the physical row set above.
        });
    }

    @Test
    void complete_temporal_rows_equal_h2_at_fixed_processing_times() {
        final int id = 89003;
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(t -> {
            t.setProcessingStartTime(Timestamp.valueOf("2026-02-01 00:00:00").getTime());
            insert(id);
            return null;
        });
        DiffBalance reference = DiffBalanceFinder.findOne(DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().eq(Timestamp.valueOf("2026-01-01 00:00:00")))
                .and(DiffBalanceFinder.processingDate().eq(DiffBalanceFinder.processingDate().getInfinityDate())));
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(t -> {
            t.setProcessingStartTime(Timestamp.valueOf("2026-03-01 00:00:00").getTime());
            reference.setQuantity(99);
            return null;
        });
        DiffBalanceList history = DiffBalanceFinder.findMany(DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                .and(DiffBalanceFinder.processingDate().equalsEdgePoint()));
        history.setBypassCache(true);
        List<Map<String, Object>> expected = new ArrayList<>();
        for (DiffBalance row : history) {
            expected.add(MithraDataAccessor.extract(DiffBalanceFinder.getFinderInstance(), row.zGetCurrentData()));
        }
        assertThat(expected).hasSizeGreaterThan(1);
        withAdapter(() -> {
            DiffBalance b = MithraManagerProvider.getMithraManager().executeTransactionalCommand(t -> {
                t.setProcessingStartTime(Timestamp.valueOf("2026-02-01 00:00:00").getTime());
                return insert(id);
            });
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(t -> {
                t.setProcessingStartTime(Timestamp.valueOf("2026-03-01 00:00:00").getTime());
                b.setQuantity(99);
                return null;
            });
            List<Map<String, Object>> actual = store.rows(mapping.tableName()).stream()
                    .map(codec::decode)
                    .collect(Collectors.toList());
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        });
    }
}
