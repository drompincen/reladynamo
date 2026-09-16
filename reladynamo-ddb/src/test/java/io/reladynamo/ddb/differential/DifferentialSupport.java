package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraDatedTransactionalObject;
import com.gs.fw.common.mithra.MithraList;
import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.TransactionalCommand;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the H2 reference side once and holds the differential comparison helpers. */
public final class DifferentialSupport {
    private static final AtomicBoolean BOOTED = new AtomicBoolean();

    private DifferentialSupport() {
    }

    public static void boot() {
        if (!BOOTED.compareAndSet(false, true)) {
            return;
        }
        try {
            StringBuilder ddl = new StringBuilder();
            try (InputStream in = DifferentialSupport.class.getResourceAsStream("/diff-schema.sql");
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    ddl.append(line).append('\n');
                }
            }
            try (Connection c = DiffH2ConnectionManager.getInstance().getConnection();
                 Statement s = c.createStatement()) {
                for (String stmt : ddl.toString().split(";")) {
                    if (stmt.trim().length() > 0) {
                        s.execute(stmt);
                    }
                }
            }
            try (InputStream in = DifferentialSupport.class.getResourceAsStream("/DiffMithraRuntime.xml")) {
                MithraManagerProvider.getMithraManager().readConfiguration(in);
            }
        } catch (Exception e) {
            BOOTED.set(false);
            throw new IllegalStateException("could not boot the H2 reference side", e);
        }
    }

    public static String loadResource(String path) {
        StringBuilder xml = new StringBuilder();
        try (InputStream in = DifferentialSupport.class.getResourceAsStream(path);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                xml.append(line).append('\n');
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not load " + path, e);
        }
        return xml.toString();
    }

    static EntityMapping parseMapping(String xmlResource) {
        return new MithraObjectXmlParser().parse(loadResource(xmlResource));
    }

    static void createPkSkTable(LocalDynamoDb ddb, String tableName) {
        ddb.client().createTable(b -> b.tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
    }

    static Store openStore(LocalDynamoDb ddb, String xmlResource) {
        EntityMapping mapping = parseMapping(xmlResource);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbWriter writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        createPkSkTable(ddb, mapping.tableName());
        return new Store(ddb, mapping, codec, writer);
    }

    static void inTransaction(TransactionalCommand<Object> command) {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(command);
    }

    /**
     * Runs {@code command} with a fixed processing start time so IN_Z/OUT_Z do not depend on the
     * wall clock. Reladomo's {@code MithraTransaction.setProcessingStartTime(long)} is the verified
     * 18.1.0 hook (javap).
     */
    static void inTransaction(long processingStartMillis, TransactionalCommand<Object> command) {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
            tx.setProcessingStartTime(processingStartMillis);
            return command.executeTransaction(tx);
        });
    }

    static Timestamp utc(int y, int mo, int d) {
        return utc(y, mo, d, 0, 0, 0, 0);
    }

    static Timestamp utc(int y, int mo, int d, int h, int mi, int s, int ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(ms * 1_000_000);
        return t;
    }

    static Timestamp infinity() {
        return com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity();
    }

    static List<Map<String, Object>> extract(RelatedFinder finder, MithraList list) {
        list.setBypassCache(true);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            rows.add(MithraDataAccessor.extract(finder, ((MithraDatedTransactionalObject) item).zGetCurrentData()));
        }
        return rows;
    }

    static String describeRows(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" row(s)\n");
        for (int i = 0; i < rows.size(); i++) {
            sb.append("  [").append(i).append("] ").append(rows.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * One mapped DynamoDB table plus the codec/writer used to push H2 snapshots through the adapter.
     */
    static final class Store {
        final LocalDynamoDb ddb;
        final EntityMapping mapping;
        final ItemCodec codec;
        final DynamoDbWriter writer;
        private final DefaultKeyStrategy keys = new DefaultKeyStrategy();

        Store(LocalDynamoDb ddb, EntityMapping mapping, ItemCodec codec, DynamoDbWriter writer) {
            this.ddb = ddb;
            this.mapping = mapping;
            this.codec = codec;
            this.writer = writer;
        }

        void push(List<Map<String, Object>> rows) {
            for (Map<String, Object> row : rows) {
                // Snapshot replay, not an ORM insert: the same H2 version may already occupy
                // this pk+sk from an earlier assertion or a bound-portal write.
                writer.upsert(row);
            }
        }

        void purgeAll(List<Map<String, Object>> rows) {
            for (Map<String, Object> row : rows) {
                writer.purge(row);
            }
        }

        List<Map<String, Object>> readPartition(String pkAttribute, int id) {
            String pk = keys.partitionKey(mapping,
                    Collections.singletonMap(pkAttribute, Integer.valueOf(id)));
            Map<String, AttributeValue> values = new HashMap<>();
            values.put(":pk", AttributeValue.builder().s(pk).build());

            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, AttributeValue> start = null;
            do {
                final Map<String, AttributeValue> exclusiveStart = start;
                software.amazon.awssdk.services.dynamodb.model.QueryResponse r = ddb.client().query(b -> {
                    b.tableName(mapping.tableName()).keyConditionExpression("pk = :pk")
                            .expressionAttributeValues(values).consistentRead(true);
                    if (exclusiveStart != null && !exclusiveStart.isEmpty()) {
                        b.exclusiveStartKey(exclusiveStart);
                    }
                });
                for (Map<String, AttributeValue> item : r.items()) {
                    rows.add(codec.decode(item));
                }
                start = r.lastEvaluatedKey();
            } while (start != null && !start.isEmpty());
            return rows;
        }

        /**
         * The original gate: take the H2 row set, put every row through the adapter, read it back,
         * require an exact match including all temporal boundaries. Does not delete extras already
         * in the partition — destructive operations must either use a fresh key or call
         * {@link #assertAgreesAfterReplay(String, int, List)}.
         */
        void assertAgrees(String pkAttribute, int id, List<Map<String, Object>> reference) {
            assertThat(reference)
                    .as("the H2 reference must produce rows, otherwise the comparison is vacuous")
                    .isNotEmpty();
            push(reference);
            List<Map<String, Object>> adapter = readPartition(pkAttribute, id);
            RowSetDiff diff = TemporalRowSetDiffer.compare(reference, adapter);
            assertThat(diff.isIdentical())
                    .as("H2 and DynamoDB disagree for %s=%s:%n%s%nH2:%n%s%nDDB:%n%s",
                            pkAttribute, Integer.valueOf(id), diff.describe(),
                            describeRows(reference), describeRows(adapter))
                    .isTrue();
        }

        /**
         * Replay the H2 snapshot as the full contents of one partition: delete whatever is already
         * stored for the key, then put the reference rows. Used for purge / non-audited destruction
         * so a vanished H2 row is also absent from DynamoDB.
         */
        void assertAgreesAfterReplay(String pkAttribute, int id, List<Map<String, Object>> reference) {
            List<Map<String, Object>> existing = readPartition(pkAttribute, id);
            purgeAll(existing);
            push(reference);
            List<Map<String, Object>> adapter = readPartition(pkAttribute, id);
            RowSetDiff diff = TemporalRowSetDiffer.compare(reference, adapter);
            assertThat(diff.isIdentical())
                    .as("H2 and DynamoDB disagree after replay for %s=%s:%n%s%nH2:%n%s%nDDB:%n%s",
                            pkAttribute, Integer.valueOf(id), diff.describe(),
                            describeRows(reference), describeRows(adapter))
                    .isTrue();
        }
    }
}
