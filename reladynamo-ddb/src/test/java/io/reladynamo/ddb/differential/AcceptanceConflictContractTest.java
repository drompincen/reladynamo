package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.MithraUniqueIndexViolationException;
import com.gs.fw.common.mithra.behavior.txparticipation.MithraOptimisticLockException;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.bridge.MithraDataAccessor;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.sql.Timestamp;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Inspection acceptance case 4: duplicate insert and concurrent/stale updates follow an
 * explicit conflict contract across JVMs. Two DynamoDbClient instances share one Local
 * table; the Reladomo portal cache is cleared between clients because a warm cache would
 * mask the DDB condition. Recovery is {@code refreshDatedObject} then retry.
 */
class AcceptanceConflictContractTest {

    private static final int ID_DUP = 96101;
    private static final int ID_STALE = 96102;

    private static LocalDynamoDb ddb;
    private static DynamoDbClient clientB;
    private static EntityMapping mapping;
    private static ItemCodec codec;
    private static DynamoDbWriter writerB;
    private static DynamoDbPersister persisterA;
    private static DynamoDbPersister persisterB;
    private static MithraObjectReader jdbcReader;
    private static MithraAbstractObjectPortal portal;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        // Same endpoint and credentials as LocalDynamoDb so -sharedDb serves one table;
        // a distinct client instance is what the coordinator keys on (IdentityHashMap).
        clientB = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + ddb.port()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();
        mapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffBalance.xml"));
        codec = new ItemCodec(mapping);
        DifferentialSupport.createPkSkTable(ddb, mapping.tableName());
        PhysicalDesign design = PhysicalDesign.builder(mapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance()).build();
        PlannerConfig config = PlannerConfig.builder().build();
        writerB = new DynamoDbWriter(clientB, mapping, codec, new DefaultKeyStrategy());
        persisterA = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping,
                new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy()),
                new QueryPlanner(), new QueryPlanExecutor(ddb.client(), codec),
                design, config);
        persisterB = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping, writerB,
                new QueryPlanner(), new QueryPlanExecutor(clientB, codec),
                design, config);
        portal = (MithraAbstractObjectPortal) DiffBalanceFinder.getMithraObjectPortal();
        jdbcReader = portal.getDatabaseObject();
    }

    @AfterAll
    static void tearDown() {
        if (portal != null && jdbcReader != null) {
            portal.setMithraObjectReader(jdbcReader);
        }
        if (clientB != null) {
            clientB.close();
        }
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void duplicate_insert_from_a_second_client_is_a_unique_index_violation() {
        // Same processing instant so both inserts address the same pk+sk. A later wall-clock
        // would be a new rectangle, which Reladomo (and the sort key) treat as a new version.
        final long insertedAt = DifferentialSupport.utc(2026, 2, 1, 9, 0, 0, 0).getTime();
        try {
            bind(persisterA);
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                tx.setProcessingStartTime(insertedAt);
                insertBalance(ID_DUP, 1.0, "first-jvm");
                return null;
            });

            // Separate Reladomo cache: a warm cache would throw (or succeed) without the DDB
            // attribute_not_exists condition ever running.
            simulateOtherJvm();
            bind(persisterB);

            Throwable error = catchThrowable(() ->
                    MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                        tx.setProcessingStartTime(insertedAt);
                        insertBalance(ID_DUP, 99.0, "second-jvm");
                        return null;
                    }));
            assertThat(error)
                    .as("duplicate insert across clients must surface MithraUniqueIndexViolationException, "
                            + "not a silent overwrite. actual=%s", messages(error))
                    .isNotNull();
            assertThat(isOrCausedBy(error, MithraUniqueIndexViolationException.class))
                    .as("expected MithraUniqueIndexViolationException in the cause chain. actual=%s",
                            messages(error))
                    .isTrue();

            simulateOtherJvm();
            bind(persisterA);
            DiffBalance stored = findCurrent(ID_DUP);
            assertThat(stored).isNotNull();
            assertThat(stored.getLabel()).isEqualTo("first-jvm");
            assertThat(stored.getQuantity()).isEqualTo(1.0);
        } finally {
            portal.setMithraObjectReader(jdbcReader);
            simulateOtherJvm();
        }
    }

    @Test
    void stale_in_place_update_is_optimistic_lock_and_retry_after_refresh_succeeds() {
        try {
            bind(persisterA);
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                insertBalance(ID_STALE, 10.0, "shared");
                return null;
            });
            DiffBalance heldByA = findCurrent(ID_STALE);
            assertThat(heldByA).isNotNull();
            Map<String, Object> original = MithraDataAccessor.extract(
                    DiffBalanceFinder.getFinderInstance(), heldByA.zGetCurrentData());

            // JVM B writes through its own client and does not touch A's Reladomo cache.
            // Going through Reladomo on B would reindex the same dated identity and mask the
            // stale expected-prior on A.
            Map<String, Object> fromB = new java.util.LinkedHashMap<String, Object>(original);
            fromB.put("note", "written-by-b");
            writerB.update(fromB, original);

            bind(persisterA);
            Throwable stale = catchThrowable(() ->
                    MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                        heldByA.setNoteUsingInPlaceUpdate("written-by-a-stale");
                        return null;
                    }));
            assertThat(stale)
                    .as("stale in-place update must fail. actual=%s", messages(stale))
                    .isNotNull();
            assertThat(isOrCausedBy(stale, MithraOptimisticLockException.class))
                    .as("expected MithraOptimisticLockException in the cause chain. actual=%s",
                            messages(stale))
                    .isTrue();
            MithraOptimisticLockException lock = findCause(stale, MithraOptimisticLockException.class);
            assertThat(lock.isRetriable())
                    .as("Reladomo retries only when isRetriable() is true")
                    .isTrue();

            MithraDataObject fresh = portal.refreshDatedObject(heldByA, false);
            assertThat(fresh)
                    .as("refreshDatedObject is the documented recovery path and must re-read B's write")
                    .isNotNull();
            Map<String, Object> extracted = MithraDataAccessor.extract(
                    DiffBalanceFinder.getFinderInstance(), fresh);
            assertThat(extracted.get("note")).isEqualTo("written-by-b");

            heldByA.zSetCurrentData(fresh);
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                heldByA.setNoteUsingInPlaceUpdate("written-by-a-after-refresh");
                return null;
            });

            simulateOtherJvm();
            bind(persisterB);
            DiffBalance afterRetry = findCurrent(ID_STALE);
            assertThat(afterRetry.getNote()).isEqualTo("written-by-a-after-refresh");
            assertThat(afterRetry.getQuantity()).isEqualTo(10.0);
            assertThat(afterRetry.getLabel()).isEqualTo("shared");
        } finally {
            portal.setMithraObjectReader(jdbcReader);
            simulateOtherJvm();
        }
    }

    private static void bind(DynamoDbPersister persister) {
        portal.setMithraObjectReader(persister);
    }

    private static void simulateOtherJvm() {
        portal.clearQueryCache();
        portal.getCache().clear();
    }

    private static void insertBalance(int id, double quantity, String label) {
        DiffBalance b = new DiffBalance(DifferentialSupport.utc(2026, 1, 1));
        b.setBalanceId(id);
        b.setQuantity(quantity);
        b.setLabel(label);
        b.insert();
    }

    private static DiffBalance findCurrent(int id) {
        Timestamp asOf = DifferentialSupport.utc(2026, 6, 1);
        return DiffBalanceFinder.findOne(DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().eq(asOf))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate())));
    }

    private static boolean isOrCausedBy(Throwable t, Class<? extends Throwable> type) {
        return findCause(t, type) != null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        while (t != null) {
            if (type.isInstance(t)) {
                return (T) t;
            }
            t = t.getCause();
        }
        return null;
    }

    private static String messages(Throwable t) {
        StringBuilder s = new StringBuilder();
        while (t != null) {
            s.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            t = t.getCause();
        }
        return s.toString();
    }
}
