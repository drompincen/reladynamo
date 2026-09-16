package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.DiffH2ConnectionManager;
import io.reladynamo.ddb.differential.DifferentialSupport;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Inspection acceptance case 10: the required SPI set is derived from a real bound-portal
 * run with cold caches and H2 genuinely disconnected. A method that is reached must work
 * or refuse by name; an unimplemented method the application actually hits is a finding.
 *
 * <p>The list this test records is what {@code docs/SUPPORT-CONTRACT.md} Tier 2 quotes.
 */
class AcceptanceRequiredSpiTest {

    /**
     * SPI methods {@link DynamoDbPersister} implements (including the null-mode
     * {@code setTxParticipationMode} no-op). Anything else is a named {@code notYet}.
     */
    static final Set<String> IMPLEMENTED = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(
                    "insert", "delete", "purge",
                    "batchInsert", "batchDelete", "batchDeleteQuietly", "batchPurge",
                    "update",
                    "find", "count",
                    "refresh", "refreshDatedObject",
                    "enrollDatedObject",
                    "setTxParticipationMode")));

    static final Set<String> NAMED_REFUSALS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(
                    "findCursor", "computeFunction", "findAggregatedData",
                    "loadFullCache", "reloadFullCache", "renewCacheForOperation",
                    "extractDatabaseIdentifiers",
                    "findForMassDelete", "deleteUsingOperation", "deleteBatchUsingOperation",
                    "batchUpdate", "multiUpdate",
                    "prepareForMassDelete", "prepareForMassPurge",
                    "getForDateRange")));

    private static LocalDynamoDb ddb;
    private static MithraAbstractObjectPortal portal;
    private static MithraObjectReader jdbcReader;
    private static RecordingPersister recording;
    private static RequestCounters counters;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        RelatedFinder finder = DiffBalanceFinder.getFinderInstance();
        EntityMapping mapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffBalance.xml"));
        PhysicalDesign design = PhysicalDesign.builder(mapping).infinityFrom(finder).build();
        new TableCreator(ddb.client()).create(design);

        counters = new RequestCounters();
        DynamoDbClient counting = CountingDynamoDb.wrap(ddb.client(), counters);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbWriter writer = new DynamoDbWriter(
                ddb.client(), mapping, codec, new DefaultKeyStrategy(), design.gsis());
        DynamoDbPersister adapter = new DynamoDbPersister(
                finder, mapping, writer,
                new QueryPlanner(), new QueryPlanExecutor(counting, codec),
                design, PlannerConfig.builder().build());
        recording = new RecordingPersister(adapter);
        portal = (MithraAbstractObjectPortal) finder.getMithraObjectPortal();
        jdbcReader = portal.getDatabaseObject();
    }

    @AfterAll
    static void tearDown() {
        DiffH2ConnectionManager.reconnectRelationalSource();
        if (portal != null && jdbcReader != null) {
            portal.setMithraObjectReader(jdbcReader);
        }
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void required_spi_methods_from_a_real_run_work_with_cold_caches_and_h2_disconnected() {
        Timestamp business = utc(2026, 3, 1);
        Timestamp infinity = DiffBalanceFinder.processingDate().getInfinityDate();

        portal.getCache().clear();
        portal.clearQueryCache();
        counters.reset();
        recording.reset();
        portal.setMithraObjectReader(recording.proxy());
        DiffH2ConnectionManager.disconnectRelationalSource();
        try {
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                DiffBalance inserted = new DiffBalance(business);
                inserted.setBalanceId(9101);
                inserted.setQuantity(11.0);
                inserted.setLabel("required-spi");
                inserted.insert();
                return null;
            });

            portal.getCache().clear();
            portal.clearQueryCache();
            counters.reset();

            DiffBalance found = MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                DiffBalance row = DiffBalanceFinder.findOne(
                        DiffBalanceFinder.balanceId().eq(9101)
                                .and(DiffBalanceFinder.businessDate().eq(business))
                                .and(DiffBalanceFinder.processingDate().eq(infinity)));
                assertThat(row)
                        .as("findOne of a just-inserted row must hit the adapter, not H2")
                        .isNotNull();
                assertThat(row.getLabel()).isEqualTo("required-spi");
                assertThat(row.getQuantity()).isEqualTo(11.0);
                row.setQuantity(22.0);
                return row;
            });
            assertThat(found).isNotNull();

            // Hold the persistent object into a new transaction: that is how classifier
            // reached refresh (Car.getWheelCount -> enrollInTransactionForRead).
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                assertThat(found.getQuantity()).isEqualTo(22.0);
                return null;
            });

            DiffBalanceList list = DiffBalanceFinder.findMany(
                    DiffBalanceFinder.balanceId().eq(9101)
                            .and(DiffBalanceFinder.businessDate().eq(business))
                            .and(DiffBalanceFinder.processingDate().eq(infinity)));
            list.setBypassCache(true);
            assertThat(list.count()).isEqualTo(1);

            RequestAssertions.requirePositiveDataReads(
                    counters.dataReads(), "required-spi find/count/update");
            RequestAssertions.requireZeroScan(counters.scan.get(), "required-spi");
            RequestAssertions.requireReaderEntered(recording.readerEntries(), "required-spi");
        } catch (RuntimeException e) {
            String msg = rootCause(e);
            if (msg.contains("not implemented yet")) {
                fail("required SPI method refused by name during the real run: " + msg
                        + "; methods reached so far: " + recording.methodsReached());
            }
            throw e;
        } finally {
            portal.setMithraObjectReader(jdbcReader);
            DiffH2ConnectionManager.reconnectRelationalSource();
        }

        Set<String> reached = recording.methodsReached();
        System.out.println("CASE-10-REQUIRED-SPI=" + reached);
        assertThat(reached)
                .as("a real application run must reach the persister")
                .isNotEmpty();
        assertThat(reached).contains("insert", "find", "update", "enrollDatedObject", "count");

        Set<String> unknown = new LinkedHashSet<String>(reached);
        unknown.removeAll(IMPLEMENTED);
        unknown.removeAll(NAMED_REFUSALS);
        assertThat(unknown)
                .as("every reached method must be classified as implemented or a named refusal")
                .isEmpty();

        Set<String> refusedButReached = new LinkedHashSet<String>(reached);
        refusedButReached.retainAll(NAMED_REFUSALS);
        assertThat(refusedButReached)
                .as("a required method that only refuses by name is the next finding; methods=%s",
                        reached)
                .isEmpty();

        for (String method : reached) {
            assertThat(IMPLEMENTED)
                    .as("reached SPI method '%s' must work; methods=%s", method, reached)
                    .contains(method);
        }
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return String.valueOf(c.getMessage());
    }

    private static Timestamp utc(int year, int month, int day) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(year, month - 1, day, 0, 0, 0);
        return new Timestamp(calendar.getTimeInMillis());
    }
}
