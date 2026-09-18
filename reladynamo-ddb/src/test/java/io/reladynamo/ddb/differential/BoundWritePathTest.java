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
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Writing through a <b>bound</b> portal, which nothing else tests.
 *
 * <p>Every differential test mirrors rows into DynamoDB by calling {@link DynamoDbWriter} directly.
 * That proves the writer works. It does not prove that Reladomo, driving the persister through a
 * bound portal and a real transaction, succeeds — and the persister refuses 20 of its 32 SPI methods,
 * including {@code enrollDatedObject}, which is declared on {@code MithraDatedObjectPersister} and so
 * exists precisely because dated objects need it.
 *
 * <p>This is the same shape of blind spot that hid finding 12: a path exercised everywhere except
 * the way an application would actually use it.
 */
class BoundWritePathTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static DynamoDbPersister adapter;
    private static ItemCodec codec;
    private static MithraObjectReader jdbcReader;
    private static MithraAbstractObjectPortal portal;

    @BeforeAll
    static void setUp() throws Exception {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();

        StringBuilder xml = new StringBuilder();
        try (InputStream in = BoundWritePathTest.class
                .getResourceAsStream("/reladomo/models/DiffBalance.xml");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                xml.append(line).append('\n');
            }
        }
        mapping = new MithraObjectXmlParser().parse(xml.toString());
        codec = new ItemCodec(mapping);

        ddb.client().createTable(b -> b.tableName(mapping.tableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));

        adapter = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping,
                new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy()),
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
    void an_insert_through_a_bound_portal_reaches_dynamodb() {
        int before = scanCount();

        portal.setMithraObjectReader(adapter);
        try {
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                DiffBalance b = new DiffBalance(utc(2026, 1, 1));
                b.setBalanceId(7001);
                b.setQuantity(11.0);
                b.setLabel("through-the-portal");
                b.insert();
                return null;
            });
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }

        assertThat(scanCount())
                .as("Reladomo drove the bound persister, so the row must be in DynamoDB")
                .isGreaterThan(before);
    }

    @Test
    void the_row_written_through_the_portal_carries_its_temporal_boundaries() {
        portal.setMithraObjectReader(adapter);
        try {
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                DiffBalance b = new DiffBalance(utc(2026, 1, 1));
                b.setBalanceId(7002);
                b.setQuantity(22.0);
                b.setLabel("boundaries");
                b.insert();
                return null;
            });
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }

        Map<String, AttributeValue> item = null;
        for (Map<String, AttributeValue> i : scanAll()) {
            if (i.containsKey("pk") && i.get("pk").s().endsWith("#7002")) {
                item = i;
            }
        }
        assertThat(item).as("row 7002 must exist in DynamoDB").isNotNull();
        assertThat(item.get("sk").s()).startsWith("v1#P#");
        assertThat(item).containsKeys("FROM_Z", "THRU_Z", "IN_Z", "OUT_Z");
    }


    @Test
    void an_update_through_a_bound_portal_succeeds_or_names_what_is_missing() {
        // The suspicious path. A dated update may route through enrollDatedObject, which refuses —
        // so this either works, or it fails with a message naming the unimplemented method rather
        // than something inscrutable. Both are acceptable; a silent no-op is not.
        portal.setMithraObjectReader(adapter);
        try {
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                DiffBalance b = new DiffBalance(utc(2026, 1, 1));
                b.setBalanceId(7003);
                b.setQuantity(33.0);
                b.setLabel("before-update");
                b.insert();
                return null;
            });

            Throwable failure = catchThrowable(() ->
                    MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                        DiffBalance found = DiffBalanceFinder.findOne(
                                DiffBalanceFinder.balanceId().eq(7003)
                                        .and(DiffBalanceFinder.businessDate().eq(utc(2026, 6, 1)))
                                        .and(DiffBalanceFinder.processingDate().eq(
                                                DiffBalanceFinder.processingDate().getInfinityDate())));
                        if (found != null) {
                            found.setQuantity(99.0);
                        }
                        return null;
                    }));

            if (failure != null) {
                // Record precisely which SPI method is missing, so COVERAGE-GAPS can name it.
                assertThat(rootCauseMessage(failure))
                        .as("an update that cannot be served must say which method is unimplemented, "
                                + "not fail opaquely. Actual: %s", rootCauseMessage(failure))
                        .containsAnyOf("not implemented yet", "enrollDatedObject", "getForDateRange",
                                "refresh", "batchUpdate", "multiUpdate");
            } else {
                // The inspection caught the old assertion here: `>= 1` was already satisfied by the
                // insert alone, so a silently no-op update passed. A bitemporal update must leave
                // BOTH the superseded 33.0 and the new 99.0 addressable.
                List<Double> versions = quantitiesFor(7003);
                assertThat(versions)
                        .as("a bitemporal update adds a version rather than replacing one; "
                                + "after inserting 33.0 and updating to 99.0 both must be stored. "
                                + "Actual: %s", versions)
                        .contains(Double.valueOf(33.0), Double.valueOf(99.0));
            }
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    @Test
    void a_terminate_through_a_bound_portal_succeeds_or_names_what_is_missing() {
        // Both transactions pin the processing clock, and to two DISTINCT instants. This is not
        // cosmetic — the assertion below is only true when the terminate lands in a later processing
        // instant than the insert, and on the wall clock it need not.
        //
        // GenericBiTemporalDirector stamps processing time as
        //     new Timestamp(tx.getProcessingStartTime() / 10 * 10)   // "clamp for sybase"
        // so every transaction start is rounded down to a 10 ms bucket. inactivateObject then reads:
        //     if (processingFrom(oldData) == txStartTime) { warn("has changed too fast. Deleting,
        //         instead of inactivating"); delete(...); }
        // An insert and a terminate that start inside the same 10 ms bucket therefore PHYSICALLY
        // DELETE the superseded version rather than closing its processing rectangle, leaving one
        // row with businessTo cut and processingTo still infinity. Verified: with both transactions
        // on one instant, H2 and DynamoDB produce byte-identical shapes —
        //   qty=44.0 biz=[2025-12-31 17:00,2026-05-31 18:00) proc=[2026-04-01 03:00,9999-12-01 23:59)
        // — so that outcome is Reladomo's semantics, not an adapter defect. This test used to run
        // both transactions on the wall clock and so failed on CI whenever they collided.
        final long insertedAt = utc(2026, 4, 1, 9, 0, 0, 0).getTime();
        final long terminatedAt = utc(2026, 4, 2, 9, 0, 0, 0).getTime();

        portal.setMithraObjectReader(adapter);
        try {
            DifferentialSupport.inTransaction(insertedAt, tx -> {
                DiffBalance b = new DiffBalance(utc(2026, 1, 1));
                b.setBalanceId(7004);
                b.setQuantity(44.0);
                b.setLabel("to-terminate");
                b.insert();
                return null;
            });

            Throwable failure = catchThrowable(() ->
                    DifferentialSupport.inTransaction(terminatedAt, tx -> {
                        DiffBalance found = DiffBalanceFinder.findOne(
                                DiffBalanceFinder.balanceId().eq(7004)
                                        .and(DiffBalanceFinder.businessDate().eq(utc(2026, 6, 1)))
                                        .and(DiffBalanceFinder.processingDate().eq(
                                                DiffBalanceFinder.processingDate().getInfinityDate())));
                        if (found != null) {
                            found.terminate();
                        }
                        return null;
                    }));

            if (failure != null) {
                assertThat(rootCauseMessage(failure))
                        .as("Actual: %s", rootCauseMessage(failure))
                        .containsAnyOf("not implemented yet", "enrollDatedObject", "getForDateRange",
                                "refresh", "batchUpdate", "multiUpdate");
            } else {
                // Previously this branch asserted nothing, so a terminate that silently did nothing
                // passed. Terminating closes the processing-time rectangle: at least one stored
                // version for 7004 must now carry a finite OUT_Z.
                List<Object> outs = decodedValuesFor(7004, "processingDateTo");
                assertThat(outs)
                        .as("terminate must leave stored versions to inspect")
                        .isNotEmpty();
                assertThat(outs).as(
                        "terminate closes a processing-time rectangle, so at least one version of "
                                + "7004 must carry a finite processingDateTo. All were still "
                                + "infinity: %s", outs)
                        .anyMatch(o -> !DifferentialSupport.infinity().equals(o));
            }
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return String.valueOf(c.getMessage());
    }


    @Test
    void a_bound_update_produces_the_same_bitemporal_shape_as_h2() {
        // "It did not throw" is a weak assertion for a write path. A bitemporal update must close the
        // old version and open a new one — so the shape is what matters, and DynamoDB must show the
        // same number of versions and the same values H2 produces for the identical operation.
        final int h2Id = 7101;
        final int ddbId = 7102;
        // Both stores run at the same two processing instants, so IN_Z and OUT_Z are comparable by
        // value. Without this the comparison can only ever fail, and would fail for a reason that
        // says nothing about the adapter.
        final long insertedAt = utc(2026, 3, 1, 9, 0, 0, 0).getTime();
        final long updatedAt = utc(2026, 3, 2, 9, 0, 0, 0).getTime();

        // Reference: the same operation against H2, through the unbound (JDBC) portal.
        insertVia(insertedAt, h2Id, 10.0, "v1");
        updateVia(updatedAt, h2Id, 55.0);

        // Adapter: identical operation, through the bound portal.
        portal.setMithraObjectReader(adapter);
        try {
            insertVia(insertedAt, ddbId, 10.0, "v1");
            updateVia(updatedAt, ddbId, 55.0);
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }

        // The 2026-09-14 inspection was right that comparing sorted quantities is too weak: a
        // bitemporal update is defined by what it does to the four boundaries, and two stores can
        // agree on {10.0, 55.0} while disagreeing about which rectangle each value occupies.
        // Compare the full temporal shape instead.
        List<String> h2Shape = shapeOf(h2RowsFor(h2Id));
        List<String> ddbShape = shapeOf(ddbRowsFor(ddbId));

        assertThat(h2Shape).as("the H2 reference must produce versions to compare against")
                .isNotEmpty();
        assertThat(ddbShape)
                .as("a bound update must leave DynamoDB with the same bitemporal shape H2 has — "
                        + "same versions, same four boundaries on each.%nH2 : %s%nDDB: %s",
                        h2Shape, ddbShape)
                .isEqualTo(h2Shape);
    }

    /** Every stored version of {@code id} on the H2 side, as attribute maps. */
    private static List<Map<String, Object>> h2RowsFor(int id) {
        io.reladynamo.ddb.differential.domain.DiffBalanceList list = DiffBalanceFinder.findMany(
                DiffBalanceFinder.balanceId().eq(id)
                        .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                        .and(DiffBalanceFinder.processingDate().equalsEdgePoint()));
        list.setBypassCache(true);
        return DifferentialSupport.extract(DiffBalanceFinder.getFinderInstance(), list);
    }

    /** Every stored version of {@code id} on the DynamoDB side, decoded to the same shape. */
    private static List<Map<String, Object>> ddbRowsFor(int id) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, AttributeValue> i : scanAll()) {
            if (i.containsKey("pk") && i.get("pk").s().endsWith("#" + id)) {
                rows.add(codec.decode(i));
            }
        }
        return rows;
    }

    /**
     * A sorted, printable projection of quantity plus all four temporal boundaries. Sorted because
     * neither store promises an order here; exact because temporal boundaries are compared by value,
     * with no millisecond tolerance.
     */
    private static List<String> shapeOf(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add("qty=" + r.get("quantity")
                    + " biz=[" + r.get("businessDateFrom") + "," + r.get("businessDateTo") + ")"
                    + " proc=[" + r.get("processingDateFrom") + "," + r.get("processingDateTo") + ")");
        }
        Collections.sort(out);
        return out;
    }

    private static void insertVia(int id, double qty, String label) {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
            DiffBalance b = new DiffBalance(utc(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(qty);
            b.setLabel(label);
            b.insert();
            return null;
        });
    }

    /**
     * As {@link #insertVia}, with the processing clock pinned. Comparing two stores that ran the same
     * operation at two different instants can never agree on IN_Z/OUT_Z — the first version of the
     * shape comparison below failed for exactly that reason, which was the test's bug, not the
     * adapter's. {@code MithraTransaction.setProcessingStartTime(long)} is the verified 18.1.0 hook.
     */
    private static void insertVia(long processingAt, int id, double qty, String label) {
        DifferentialSupport.inTransaction(processingAt, tx -> {
            DiffBalance b = new DiffBalance(utc(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(qty);
            b.setLabel(label);
            b.insert();
            return null;
        });
    }

    private static void updateVia(long processingAt, int id, double qty) {
        DifferentialSupport.inTransaction(processingAt, tx -> {
            DiffBalance found = DiffBalanceFinder.findOne(
                    DiffBalanceFinder.balanceId().eq(id)
                            .and(DiffBalanceFinder.businessDate().eq(utc(2026, 6, 1)))
                            .and(DiffBalanceFinder.processingDate().eq(
                                    DiffBalanceFinder.processingDate().getInfinityDate())));
            assertThat(found)
                    .as("balance %d was inserted through this portal and must be findable through it "
                            + "before it can be updated; a null here means the read path, not the "
                            + "update path, is what is broken", id)
                    .isNotNull();
            found.setQuantity(qty);
            return null;
        });
    }

    /**
     * The 2026-09-14 inspection caught this helper skipping its own mutation: it used to write
     * {@code if (found != null) found.setQuantity(qty);}, so a bound portal that could not read back
     * the row it had just inserted produced a green test that had updated nothing. A finder that
     * cannot see its own insert <em>is</em> the finding — assert it rather than routing around it.
     */
    private static void updateVia(int id, double qty) {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
            DiffBalance found = DiffBalanceFinder.findOne(
                    DiffBalanceFinder.balanceId().eq(id)
                            .and(DiffBalanceFinder.businessDate().eq(utc(2026, 6, 1)))
                            .and(DiffBalanceFinder.processingDate().eq(
                                    DiffBalanceFinder.processingDate().getInfinityDate())));
            assertThat(found)
                    .as("balance %d was inserted through this portal and must be findable through it "
                            + "before it can be updated; a null here means the read path, not the "
                            + "update path, is what is broken", id)
                    .isNotNull();
            found.setQuantity(qty);
            return null;
        });
    }

    private static List<Map<String, AttributeValue>> scanAll() {
        ScanResponse r = ddb.client().scan(b -> b.tableName(mapping.tableName()));
        return r.items();
    }

    private static int scanCount() {
        return scanAll().size();
    }

    /** Every stored version of {@code id}, decoded, projected onto one attribute. */
    private static List<Object> decodedValuesFor(int id, String attribute) {
        List<Object> out = new ArrayList<>();
        for (Map<String, AttributeValue> i : scanAll()) {
            if (i.containsKey("pk") && i.get("pk").s().endsWith("#" + id)) {
                out.add(codec.decode(i).get(attribute));
            }
        }
        return out;
    }

    /**
     * Quantities decoded through the codec rather than read off the raw AttributeValue: doubles are
     * stored as IEEE-754 binary, not {@code N}, so {@code .n()} is null and {@code .n()} on it NPEs.
     * That mistake has already been made once in this suite.
     */
    private static List<Double> quantitiesFor(int id) {
        List<Double> out = new ArrayList<>();
        for (Object q : decodedValuesFor(id, "quantity")) {
            out.add(Double.valueOf(String.valueOf(q)));
        }
        return out;
    }

    private static Timestamp utc(int y, int mo, int d) {
        return utc(y, mo, d, 0, 0, 0, 0);
    }

    private static Timestamp utc(int y, int mo, int d, int h, int mi, int sec, int ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, sec);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(ms * 1_000_000);
        return t;
    }
}