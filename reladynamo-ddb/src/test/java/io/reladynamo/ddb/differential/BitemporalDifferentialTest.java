package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.MithraTransaction;
import com.gs.fw.common.mithra.TransactionalCommand;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The differential gate: one set of bitemporal operations, two stores, identical results.
 *
 * <p><b>What this proves.</b> Real Reladomo bitemporal operations run against H2, producing whatever
 * row set the {@code TemporalDirector} decides should exist. Those rows are then written through the
 * adapter into DynamoDB and read back. Every attribute, including all four temporal boundaries, must
 * match exactly.
 *
 * <p><b>What it does not yet prove.</b> The read path does not yet materialise Reladomo objects, so
 * DynamoDB is queried for raw rows rather than through a finder. That means this pins storage,
 * key derivation and codec fidelity — not query planning end-to-end. Stating the boundary matters:
 * a gate whose scope is vague is a gate nobody can trust.
 */
class BitemporalDifferentialTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static DynamoDbWriter writer;
    private static ItemCodec codec;

    @BeforeAll
    static void setUp() throws Exception {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();

        StringBuilder xml = new StringBuilder();
        try (InputStream in = BitemporalDifferentialTest.class
                .getResourceAsStream("/reladomo/models/DiffBalance.xml");
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                xml.append(line).append('\n');
            }
        }
        // The mapping comes from the SAME XML the generated objects came from — that is what makes
        // this a test of the generic adapter rather than of a hand-written mapping.
        mapping = new MithraObjectXmlParser().parse(xml.toString());
        codec = new ItemCodec(mapping);
        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());

        ddb.client().createTable(b -> b.tableName(mapping.tableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void an_insert_round_trips_identically_through_both_stores() {
        int id = 1;
        inTransaction(tx -> {
            DiffBalance b = new DiffBalance(businessDate(2026, 6, 1));
            b.setBalanceId(id);
            b.setQuantity(100.5);
            b.setLabel("opening");
            b.insert();
            return null;
        });

        assertStoresAgree(id);
    }

    @Test
    void a_retroactive_correction_produces_the_same_row_set_in_both_stores() {
        // The case the whole adapter exists for: Reladomo's director turns this into multiple rows
        // with distinct temporal boundaries. If DynamoDB collapses or reorders them, the diff fails.
        int id = 2;
        inTransaction(tx -> {
            DiffBalance b = new DiffBalance(businessDate(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(10.0);
            b.setLabel("original");
            b.insert();
            return null;
        });
        inTransaction(tx -> {
            DiffBalance found = DiffBalanceFinder.findOne(
                    DiffBalanceFinder.balanceId().eq(id)
                            .and(DiffBalanceFinder.businessDate().eq(businessDate(2026, 6, 1))));
            if (found != null) {
                found.setQuantity(99.0);
            }
            return null;
        });

        assertStoresAgree(id);
    }

    @Test
    void a_terminate_produces_the_same_row_set_in_both_stores() {
        int id = 3;
        inTransaction(tx -> {
            DiffBalance b = new DiffBalance(businessDate(2026, 1, 1));
            b.setBalanceId(id);
            b.setQuantity(5.0);
            b.setLabel("to-terminate");
            b.insert();
            return null;
        });
        inTransaction(tx -> {
            DiffBalance found = DiffBalanceFinder.findOne(
                    DiffBalanceFinder.balanceId().eq(id)
                            .and(DiffBalanceFinder.businessDate().eq(businessDate(2026, 6, 1))));
            if (found != null) {
                found.terminate();
            }
            return null;
        });

        assertStoresAgree(id);
    }

    /**
     * Reads every version of one balance from H2, mirrors it into DynamoDB, reads it back, and
     * requires an exact match on every attribute and all four temporal boundaries.
     */
    private void assertStoresAgree(int balanceId) {
        List<Map<String, Object>> reference = readAllVersionsFromH2(balanceId);
        assertThat(reference)
                .as("the H2 reference must produce rows, otherwise the comparison is vacuous")
                .isNotEmpty();

        for (Map<String, Object> row : reference) {
            writer.upsert(row);
        }

        List<Map<String, Object>> adapter = readAllVersionsFromDynamo(balanceId);
        RowSetDiff diff = TemporalRowSetDiffer.compare(reference, adapter);
        assertThat(diff.isIdentical())
                .as("H2 and DynamoDB disagree for balanceId=%s:%n%s", balanceId, diff.describe())
                .isTrue();
    }

    private List<Map<String, Object>> readAllVersionsFromH2(int balanceId) {
        // Every processing-time version, not just the current one — the audit trail is the part most
        // likely to be lost by a storage layer that treats a correction as an overwrite.
        DiffBalanceList list = DiffBalanceFinder.findMany(
                DiffBalanceFinder.balanceId().eq(balanceId)
                        .and(DiffBalanceFinder.businessDate().equalsEdgePoint())
                        .and(DiffBalanceFinder.processingDate().equalsEdgePoint()));
        list.setBypassCache(true);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            rows.add(MithraDataAccessor.extract(
                    DiffBalanceFinder.getFinderInstance(), list.get(i).zGetCurrentData()));
        }
        return rows;
    }

    private List<Map<String, Object>> readAllVersionsFromDynamo(int balanceId) {
        String pk = new DefaultKeyStrategy().partitionKey(mapping,
                java.util.Collections.singletonMap("balanceId", Integer.valueOf(balanceId)));
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

    private static void inTransaction(TransactionalCommand<Object> command) {
        MithraManagerProvider.getMithraManager().executeTransactionalCommand(command);
    }

    private static Timestamp businessDate(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
