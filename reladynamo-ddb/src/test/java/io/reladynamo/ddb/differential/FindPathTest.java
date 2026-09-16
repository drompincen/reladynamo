package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards on {@code DynamoDbPersister.find()}.
 *
 * <p>The materialisation chain itself is covered where each link lives; what is pinned here is the
 * refusal. A dated object materialised at a guessed as-of date returns rows that look entirely
 * plausible and are silently the wrong version of the truth — the single worst failure this adapter
 * could have, because nothing about the result announces it.
 */
class FindPathTest {

    private static LocalDynamoDb ddb;
    private static DynamoDbPersister persister;

    @BeforeAll
    static void setUp() throws Exception {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();

        StringBuilder xml = new StringBuilder();
        try (InputStream in = FindPathTest.class.getResourceAsStream("/reladomo/models/DiffBalance.xml");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                xml.append(line).append('\n');
            }
        }
        EntityMapping mapping = new MithraObjectXmlParser().parse(xml.toString());
        ItemCodec codec = new ItemCodec(mapping);

        ddb.client().createTable(b -> b.tableName(mapping.tableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));

        persister = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(), mapping,
                new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy()),
                new QueryPlanner(), new QueryPlanExecutor(ddb.client(), codec),
                PhysicalDesign.builder(mapping).infinityFrom(DiffBalanceFinder.getFinderInstance()).build(), PlannerConfig.builder().build());
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void refuses_to_materialise_a_dated_object_without_an_as_of_equality() {
        // balanceId alone, with no businessDate/processingDate equality. Reladomo will inject its
        // defaults for a normal finder call; this asserts that when they are genuinely absent the
        // adapter refuses rather than inventing a date.
        Operation op = DiffBalanceFinder.balanceId().eq(1);
        assertThatThrownBy(() -> persister.find(new AnalyzedOperation(op), null, false, 0, 0, false, false))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("cannot materialise")
                .hasMessageContaining("as-of equality for 'businessDate'")
                .hasMessageContaining("Picking a default date would return rows that look right")
                .hasMessageContaining("silently the wrong version");
    }

    @Test
    void a_write_only_persister_refuses_find_by_name() {
        DynamoDbPersister writeOnly = new DynamoDbPersister(
                DiffBalanceFinder.getFinderInstance(),
                persisterMapping(), writerOf());
        assertThatThrownBy(() -> writeOnly.find(null, null, false, 0, 0, false, false))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("find");
    }

    private static EntityMapping persisterMapping() {
        try {
            StringBuilder xml = new StringBuilder();
            try (InputStream in = FindPathTest.class.getResourceAsStream("/reladomo/models/DiffBalance.xml");
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    xml.append(line).append('\n');
                }
            }
            return new MithraObjectXmlParser().parse(xml.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DynamoDbWriter writerOf() {
        EntityMapping m = persisterMapping();
        return new DynamoDbWriter(ddb.client(), m, new ItemCodec(m), new DefaultKeyStrategy());
    }

    private static Timestamp utc(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
