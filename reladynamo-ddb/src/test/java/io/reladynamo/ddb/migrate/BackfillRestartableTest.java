package io.reladynamo.ddb.migrate;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-04: a backfill that only works as an in-memory one-shot is not a migration tool.
 * These tests require streaming intake, linear (not quadratic) verification reads,
 * durable kill/resume, snapshot identity, a valid-empty outcome, and a real write-rate cap.
 */
class BackfillRestartableTest {

    private static LocalDynamoDb ddb;
    private static int tables;

    @TempDir
    Path tmp;

    @BeforeAll
    static void setUp() {
        ddb = LocalDynamoDb.start();
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void should_keep_peak_buffer_to_one_partition_when_history_has_many_keys() {
        int keys = 40;
        int versions = 15;
        Fixture f = fixture("bf_mem");
        BackfillConfig config = BackfillConfig.builder()
                .maxBufferedRows(32)
                .build();

        BackfillResult r = f.backfill.run(groupedHistory(keys, versions), config);

        assertThat(r.verified()).isTrue();
        assertThat(r.rowsRead()).isEqualTo(keys * versions);
        assertThat(r.rowsWritten()).isEqualTo(keys * versions);
        // Bound: one logical key at a time. 40×15 = 600 source rows, but only 15 live.
        assertThat(r.peakBufferedRows())
                .as("must not hold the whole source; bound is one partition's versions")
                .isEqualTo(versions);
        assertThat(r.peakBufferedRows()).isLessThanOrEqualTo(config.maxBufferedRows());
    }

    @Test
    void should_read_each_partition_once_not_once_per_version() {
        int keys = 10;
        int versions = 20;
        AtomicInteger queries = new AtomicInteger();
        Fixture f = fixture("bf_lin", queries, new AtomicInteger());

        BackfillResult r = f.backfill.run(groupedHistory(keys, versions), BackfillConfig.defaults());

        assertThat(r.verified()).isTrue();
        assertThat(r.partitionReads())
                .as("group by logical key and read each partition once: O(K), not O(K*V)")
                .isEqualTo(keys);
        assertThat(queries.get())
                .as("DynamoDB Query round-trips must be linear in keys, not versions")
                .isEqualTo(keys);
        assertThat(queries.get()).isLessThan(keys * versions);
    }

    @Test
    void should_resume_after_kill_mid_chunk_without_duplicates_or_gaps() {
        Path storeFile = tmp.resolve("backfill.chk");
        BackfillCheckpointStore store = new FileBackfillCheckpointStore(storeFile);
        AtomicInteger batchWrites = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        int killOn = 3;
        Fixture f = fixture("bf_kill", queries, batchWrites, killOn);

        int p1Versions = 10;
        int p2Versions = 40;
        List<Map<String, Object>> source = new ArrayList<Map<String, Object>>();
        source.addAll(versionsOf(1, p1Versions));
        source.addAll(versionsOf(2, p2Versions));

        BackfillConfig config = BackfillConfig.builder()
                .maxBufferedRows(25)
                .snapshotId("snap-kill-1")
                .checkpointStore(store)
                .build();

        assertThatThrownBy(() -> f.backfill.run(source.iterator(), config))
                .hasMessageContaining("simulated kill");

        BackfillCheckpoint afterKill = store.load();
        assertThat(afterKill).isNotNull();
        assertThat(afterKill.snapshotId()).isEqualTo("snap-kill-1");
        assertThat(afterKill.completedPartitions()).hasSize(1);
        assertThat(afterKill.rowsRead()).isEqualTo(p1Versions);
        assertThat(afterKill.rowsWritten()).isEqualTo(p1Versions);

        Fixture resumed = fixtureReuse(f, queries, new AtomicInteger(), 0);
        BackfillResult r = resumed.backfill.run(source.iterator(), config);

        assertThat(r.verified()).isTrue();
        assertThat(r.rowsRead()).isEqualTo(p1Versions + p2Versions);
        assertThat(r.rowsWritten()).isEqualTo(p1Versions + p2Versions);
        assertThat(resumed.backfill.readBack(1)).hasSize(p1Versions);
        assertThat(resumed.backfill.readBack(2)).hasSize(p2Versions);
        ScanResponse scan = ddb.client().scan(b -> b.tableName(f.mapping.tableName()));
        assertThat(scan.count())
                .as("resume must neither duplicate nor skip versions")
                .isEqualTo(p1Versions + p2Versions);
    }

    @Test
    void should_not_checkpoint_a_diverged_partition_when_a_later_key_succeeds() {
        Path storeFile = tmp.resolve("diverge.chk");
        BackfillCheckpointStore store = new FileBackfillCheckpointStore(storeFile);
        Fixture f = fixture("bf_divchk");
        List<Map<String, Object>> source = new ArrayList<Map<String, Object>>();
        source.addAll(versionsOf(1, 3));
        source.addAll(versionsOf(2, 3));

        f.writer.upsert(datedRow(1, 99.0, tsMillis(9 * 86_400_000L)));

        BackfillConfig config = BackfillConfig.builder()
                .snapshotId("snap-div")
                .checkpointStore(store)
                .build();
        BackfillResult r = f.backfill.run(source, config);
        assertThat(r.verified()).isFalse();
        assertThat(joined(r).toLowerCase()).containsAnyOf("extra", "duplicate");

        BackfillCheckpoint chk = store.load();
        assertThat(chk).isNotNull();
        assertThat(chk.completedPartitions())
                .as("a partition that failed verify must not be marked done")
                .noneMatch(p -> p.contains("balanceId=1"))
                .anyMatch(p -> p.contains("balanceId=2"));

        BackfillResult again = f.backfill.run(source, config);
        assertThat(again.verified()).isFalse();
        assertThat(joined(again).toLowerCase()).containsAnyOf("extra", "duplicate");
    }

    @Test
    void should_refuse_when_snapshot_identifier_changes_between_runs() {
        Path storeFile = tmp.resolve("snap.chk");
        BackfillCheckpointStore store = new FileBackfillCheckpointStore(storeFile);
        Fixture f = fixture("bf_snap");
        List<Map<String, Object>> first = versionsOf(7, 5);
        BackfillConfig snap1 = BackfillConfig.builder()
                .snapshotId("snap-A")
                .checkpointStore(store)
                .build();
        assertThat(f.backfill.run(first, snap1).verified()).isTrue();

        List<Map<String, Object>> changed = versionsOf(8, 5);
        BackfillConfig snap2 = BackfillConfig.builder()
                .snapshotId("snap-B")
                .checkpointStore(store)
                .build();
        assertThatThrownBy(() -> f.backfill.run(changed, snap2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot")
                .hasMessageContaining("snap-A")
                .hasMessageContaining("snap-B");
    }

    @Test
    void should_report_valid_empty_table_when_empty_source_is_declared() {
        Fixture f = fixture("bf_empty_ok");
        BackfillConfig config = BackfillConfig.builder()
                .allowEmptySource(true)
                .build();

        BackfillResult r = f.backfill.run(new ArrayList<Map<String, Object>>(), config);

        assertThat(r.rowsRead()).isZero();
        assertThat(r.rowsWritten()).isZero();
        assertThat(r.validEmptyTable()).isTrue();
        assertThat(r.verified()).isTrue();
        assertThat(r.summary()).containsIgnoringCase("valid-empty");
        assertThat(r.summary()).doesNotContain("Check the source query");
    }

    @Test
    void should_still_refuse_empty_source_when_not_declared_valid() {
        Fixture f = fixture("bf_empty_bad");
        BackfillResult r = f.backfill.run(new ArrayList<Map<String, Object>>());

        assertThat(r.rowsRead()).isZero();
        assertThat(r.validEmptyTable()).isFalse();
        assertThat(r.verified()).isFalse();
        assertThat(r.summary()).containsIgnoringCase("empty");
        assertThat(r.summary()).containsIgnoringCase("source query");
    }

    @Test
    void should_limit_write_rate_to_configured_cap() {
        Fixture f = fixture("bf_rate");
        int writes = 8;
        int perSecond = 8;
        List<Map<String, Object>> source = versionsOf(3, writes);
        BackfillConfig config = BackfillConfig.builder()
                .maxWritesPerSecond(perSecond)
                .build();

        long start = System.nanoTime();
        BackfillResult r = f.backfill.run(source, config);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(r.verified()).isTrue();
        // 8 writes at 8/s, first is immediate: ~7 intervals of 125ms ≈ 875ms.
        assertThat(elapsedMs)
                .as("rate limiter must actually delay writes, not only store a number")
                .isGreaterThanOrEqualTo(700L);
    }

    @Test
    void should_refuse_silly_rate_limit_and_buffer_values() {
        assertThatThrownBy(() -> BackfillConfig.builder().maxWritesPerSecond(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("writesPerSecond");
        assertThatThrownBy(() -> BackfillConfig.builder().maxWritesPerSecond(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackfillConfig.builder().maxWritesPerSecond(40_001).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackfillConfig.builder().maxWritesPerSecond(Integer.MAX_VALUE).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackfillConfig.builder().maxBufferedRows(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackfillConfig.builder().maxBufferedRows(-5).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackfillConfig.builder().maxBufferedRows(10_001).build())
                .isInstanceOf(IllegalArgumentException.class);
        BackfillConfig ok = BackfillConfig.builder().maxWritesPerSecond(1).maxBufferedRows(1).build();
        assertThat(ok.maxWritesPerSecond()).isEqualTo(Integer.valueOf(1));
        assertThat(ok.maxBufferedRows()).isEqualTo(1);
    }

    @Test
    void should_accept_iterator_without_requiring_a_list() {
        Fixture f = fixture("bf_iter");
        Iterator<Map<String, Object>> it = groupedHistory(5, 3).iterator();
        assertThat(it).isNotInstanceOf(List.class);

        BackfillResult r = f.backfill.run(it, BackfillConfig.defaults());

        assertThat(r.verified()).isTrue();
        assertThat(r.rowsRead()).isEqualTo(15);
        assertThat(r.rowsWritten()).isEqualTo(15);
        assertThat(r.peakBufferedRows()).isEqualTo(3);
    }

    @Test
    void should_still_report_payload_divergence_after_a_streaming_copy() {
        Fixture f = fixture("bf_m01");
        Map<String, Object> original = datedRow(11, 10.0, tsMillis(0));
        assertThat(f.backfill.run(java.util.Collections.singletonList(original)).verified()).isTrue();

        f.writer.upsert(datedRow(11, 99.0, tsMillis(0)));
        BackfillResult r = f.backfill.verify(java.util.Collections.singletonList(original));

        assertThat(r.verified()).isFalse();
        assertThat(r.rowsWritten()).isLessThan(r.rowsRead());
        assertThat(joined(r)).contains("quantity");
    }

    // --- fixtures -------------------------------------------------------------------

    private Fixture fixture(String tablePrefix) {
        return fixture(tablePrefix, new AtomicInteger(), new AtomicInteger(), 0);
    }

    private Fixture fixture(String tablePrefix, AtomicInteger queries, AtomicInteger batchWrites) {
        return fixture(tablePrefix, queries, batchWrites, 0);
    }

    private Fixture fixture(String tablePrefix, AtomicInteger queries, AtomicInteger batchWrites,
                            int killOnBatchWrite) {
        String table = tablePrefix + "_" + (++tables);
        EntityMapping mapping = mappingFor(table);
        createTable(table);
        DynamoDbClient client = countingClient(ddb.client(), queries, batchWrites, killOnBatchWrite);
        ItemCodec codec = new ItemCodec(mapping);
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        DynamoDbWriter writer = new DynamoDbWriter(client, mapping, codec, keys);
        Backfill backfill = new Backfill(client, mapping, codec, keys, writer);
        return new Fixture(mapping, writer, backfill);
    }

    private Fixture fixtureReuse(Fixture original, AtomicInteger queries, AtomicInteger batchWrites,
                                 int killOnBatchWrite) {
        DynamoDbClient client = countingClient(ddb.client(), queries, batchWrites, killOnBatchWrite);
        ItemCodec codec = new ItemCodec(original.mapping);
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        DynamoDbWriter writer = new DynamoDbWriter(client, original.mapping, codec, keys);
        Backfill backfill = new Backfill(client, original.mapping, codec, keys, writer);
        return new Fixture(original.mapping, writer, backfill);
    }

    private static EntityMapping mappingFor(String table) {
        return new EntityMapping("com.acme.Balance", table,
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, infinity()),
                Arrays.asList(
                        new AttributeMapping("balanceId", "balanceId", "int", true, false),
                        new AttributeMapping("quantity", "quantity", "double", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
    }

    private static void createTable(String tableName) {
        ddb.client().createTable(b -> b.tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
    }

    private static DynamoDbClient countingClient(DynamoDbClient inner, AtomicInteger queries,
                                                 AtomicInteger batchWrites, int killOnBatchWrite) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("query".equals(name)) {
                    queries.incrementAndGet();
                }
                if ("batchWriteItem".equals(name)) {
                    int n = batchWrites.incrementAndGet();
                    if (killOnBatchWrite > 0 && n == killOnBatchWrite) {
                        throw new RuntimeException("simulated kill");
                    }
                }
                try {
                    return method.invoke(inner, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    if (e.getCause() != null) {
                        throw e.getCause();
                    }
                    throw e;
                }
            }
        };
        return (DynamoDbClient) Proxy.newProxyInstance(
                DynamoDbClient.class.getClassLoader(),
                new Class[]{DynamoDbClient.class},
                handler);
    }

    private static Iterable<Map<String, Object>> groupedHistory(int keys, int versions) {
        return new Iterable<Map<String, Object>>() {
            @Override
            public Iterator<Map<String, Object>> iterator() {
                return new Iterator<Map<String, Object>>() {
                    private int key = 1;
                    private int version = 0;

                    @Override
                    public boolean hasNext() {
                        return key <= keys;
                    }

                    @Override
                    public Map<String, Object> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        Map<String, Object> row = datedRow(key, version + 1.0, tsMillis(version * 86_400_000L));
                        version++;
                        if (version == versions) {
                            version = 0;
                            key++;
                        }
                        return row;
                    }
                };
            }
        };
    }

    private static List<Map<String, Object>> versionsOf(int id, int versions) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int v = 0; v < versions; v++) {
            rows.add(datedRow(id, v + 1.0, tsMillis(v * 86_400_000L)));
        }
        return rows;
    }

    private static Map<String, Object> datedRow(int id, double qty, Timestamp from) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("balanceId", Integer.valueOf(id));
        m.put("quantity", Double.valueOf(qty));
        m.put("businessDateFrom", from);
        m.put("businessDateTo", infinity());
        m.put("processingDateFrom", from);
        m.put("processingDateTo", infinity());
        return m;
    }

    private static Timestamp tsMillis(long millis) {
        return new Timestamp(millis);
    }

    private static Timestamp infinity() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(9999, Calendar.DECEMBER, 1, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }

    private static String joined(BackfillResult r) {
        StringBuilder sb = new StringBuilder();
        for (String d : r.divergences()) {
            sb.append(d).append('\n');
        }
        return sb.toString();
    }

    private static final class Fixture {
        final EntityMapping mapping;
        final DynamoDbWriter writer;
        final Backfill backfill;

        Fixture(EntityMapping mapping, DynamoDbWriter writer, Backfill backfill) {
            this.mapping = mapping;
            this.writer = writer;
            this.backfill = backfill;
        }
    }
}
