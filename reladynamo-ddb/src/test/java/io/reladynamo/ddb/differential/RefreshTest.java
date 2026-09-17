package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.MithraDataObject;
import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.bridge.MithraDataFactory;
import io.reladynamo.core.bridge.MithraDataPopulator;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code DynamoDbPersister.refresh} / {@code refreshDatedObject} against DynamoDB Local.
 *
 * <p>A no-op that returned the argument unchanged would pass a round-trip of an unmodified
 * row and is how this claim stayed green before. These tests change the stored item
 * underneath the in-memory data, then require the re-read to observe that change — and,
 * for a dated object, to observe <em>that</em> rectangle rather than a later current one.
 */
class RefreshTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static ItemCodec codec;
    private static DynamoDbWriter writer;
    private static DynamoDbPersister persister;
    private static RelatedFinder finder;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();
        mapping = new MithraObjectXmlParser().parse(loadXml());
        codec = new ItemCodec(mapping);
        finder = DiffBalanceFinder.getFinderInstance();

        ddb.client().createTable(b -> b.tableName(mapping.tableName())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));

        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        persister = new DynamoDbPersister(finder, mapping, writer);
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void should_return_new_payload_when_item_was_modified_externally() {
        Timestamp from = utc(2026, 6, 1);
        Map<String, Object> original = row(8101, 10.0, "original", from, from);
        writer.insert(original);

        MithraDataObject stale = dataOf(original);

        Map<String, Object> modified = copy(original);
        modified.put("quantity", Double.valueOf(77.0));
        modified.put("label", "externally-changed");
        writer.upsert(modified);

        MithraDataObject fresh = persister.refresh(stale, false);

        assertThat(fresh).isNotNull();
        Map<String, Object> extracted = MithraDataAccessor.extract(finder, fresh);
        assertThat(extracted.get("quantity")).isEqualTo(Double.valueOf(77.0));
        assertThat(extracted.get("label")).isEqualTo("externally-changed");
        assertThat(extracted.get("businessDateFrom")).isEqualTo(from);
        assertThat(extracted.get("processingDateFrom")).isEqualTo(from);
        assertThat(extracted.get("balanceId")).isEqualTo(Integer.valueOf(8101));
    }

    @Test
    void should_return_the_addressed_rectangle_not_the_current_version() {
        Timestamp oldBizFrom = utc(2020, 1, 1);
        Timestamp oldBizTo = utc(2021, 1, 1);
        Timestamp oldProcFrom = utc(2020, 1, 1);
        Timestamp oldProcTo = utc(2021, 6, 1);
        Timestamp newBizFrom = utc(2021, 1, 1);
        Timestamp newProcFrom = utc(2021, 6, 1);

        Map<String, Object> closed = row(8102, 1.0, "closed", oldBizFrom, oldProcFrom);
        closed.put("businessDateTo", oldBizTo);
        closed.put("processingDateTo", oldProcTo);

        Map<String, Object> current = row(8102, 99.0, "current", newBizFrom, newProcFrom);

        writer.insert(closed);
        writer.insert(current);

        DiffBalance dated = new DiffBalance(oldBizFrom, oldProcFrom);
        dated.zSetCurrentData(dataOf(closed));

        MithraDataObject fresh = persister.refreshDatedObject(dated, false);

        assertThat(fresh).isNotNull();
        Map<String, Object> extracted = MithraDataAccessor.extract(finder, fresh);
        assertThat(extracted.get("quantity"))
                .as("refreshDatedObject must re-read the closed rectangle, not substitute the current one")
                .isEqualTo(Double.valueOf(1.0));
        assertThat(extracted.get("label")).isEqualTo("closed");
        assertThat(extracted.get("businessDateFrom")).isEqualTo(oldBizFrom);
        assertThat(extracted.get("businessDateTo")).isEqualTo(oldBizTo);
        assertThat(extracted.get("processingDateFrom")).isEqualTo(oldProcFrom);
        assertThat(extracted.get("processingDateTo")).isEqualTo(oldProcTo);
    }

    @Test
    void should_carry_state_a_conditional_update_can_lock_against_after_refresh() {
        Timestamp from = utc(2026, 7, 1);
        Map<String, Object> original = row(8103, 10.0, "shared", from, from);
        writer.insert(original);

        Map<String, Object> fromA = copy(original);
        fromA.put("quantity", Double.valueOf(20.0));
        fromA.put("label", "writer-a");
        writer.update(fromA, original);

        MithraDataObject fresh = persister.refresh(dataOf(original), false);
        Map<String, Object> expectedPrior = MithraDataAccessor.extract(finder, fresh);

        Map<String, Object> retry = copy(expectedPrior);
        retry.put("quantity", Double.valueOf(30.0));
        retry.put("label", "retry-after-refresh");
        writer.update(retry, expectedPrior);

        MithraDataObject afterRetry = persister.refresh(fresh, false);
        Map<String, Object> stored = MithraDataAccessor.extract(finder, afterRetry);
        assertThat(stored.get("quantity")).isEqualTo(Double.valueOf(30.0));
        assertThat(stored.get("label")).isEqualTo("retry-after-refresh");
    }

    @Test
    void should_refresh_when_lockInDatabase_is_true_because_dynamodb_has_no_pessimistic_lock() {
        Timestamp from = utc(2026, 8, 1);
        Map<String, Object> original = row(8104, 4.0, "unlocked", from, from);
        writer.insert(original);

        Map<String, Object> modified = copy(original);
        modified.put("quantity", Double.valueOf(5.0));
        modified.put("label", "still-consistent");
        writer.upsert(modified);

        MithraDataObject fresh = persister.refresh(dataOf(original), true);

        assertThat(fresh).isNotNull();
        Map<String, Object> extracted = MithraDataAccessor.extract(finder, fresh);
        assertThat(extracted.get("quantity")).isEqualTo(Double.valueOf(5.0));
        assertThat(extracted.get("label")).isEqualTo("still-consistent");
    }

    @Test
    void should_return_null_when_the_addressed_item_is_gone() {
        Timestamp from = utc(2026, 9, 1);
        Map<String, Object> original = row(8105, 1.0, "gone", from, from);
        writer.insert(original);
        writer.delete(original);

        assertThat(persister.refresh(dataOf(original), false)).isNull();
    }

    /**
     * Coordinator policy ({@code RELADYNAMO-TXN-006}): database reads while this
     * transaction has staged writes are refused. Refresh is a GetItem, so it must
     * not return the committed prior while an uncommitted Put sits in the buffer —
     * that would be a second isolation policy, visible to {@code find}/{@code count}
     * as a refusal and to refresh as a stale value.
     */
    @Test
    void should_refuse_refresh_when_this_transaction_has_staged_uncommitted_writes() {
        Timestamp from = utc(2026, 10, 1);
        Map<String, Object> original = row(8106, 10.0, "committed", from, from);
        writer.insert(original);

        Map<String, Object> staged = copy(original);
        staged.put("quantity", Double.valueOf(99.0));
        staged.put("label", "staged-not-committed");

        assertThatThrownBy(() -> MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
            writer.update(staged, original);
            persister.refresh(dataOf(original), false);
            return null;
        })).hasStackTraceContaining("RELADYNAMO-TXN-006");
    }

    @Test
    void should_refresh_inside_a_transaction_that_has_not_staged_writes() {
        Timestamp from = utc(2026, 11, 1);
        Map<String, Object> original = row(8107, 3.0, "already-committed", from, from);
        writer.insert(original);

        MithraDataObject fresh = MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx ->
                persister.refresh(dataOf(original), false));

        assertThat(fresh).isNotNull();
        Map<String, Object> extracted = MithraDataAccessor.extract(finder, fresh);
        assertThat(extracted.get("quantity")).isEqualTo(Double.valueOf(3.0));
        assertThat(extracted.get("label")).isEqualTo("already-committed");
    }

    private static MithraDataObject dataOf(Map<String, Object> row) {
        MithraDataObject data = MithraDataFactory.newData(finder);
        MithraDataPopulator.populate(finder, data, row);
        return data;
    }

    private static Map<String, Object> row(int id, double quantity, String label,
                                           Timestamp businessFrom, Timestamp processingFrom) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("balanceId", Integer.valueOf(id));
        m.put("quantity", Double.valueOf(quantity));
        m.put("label", label);
        m.put("note", null);
        m.put("businessDateFrom", businessFrom);
        m.put("businessDateTo", infinity());
        m.put("processingDateFrom", processingFrom);
        m.put("processingDateTo", infinity());
        return m;
    }

    private static Map<String, Object> copy(Map<String, Object> row) {
        return new LinkedHashMap<String, Object>(row);
    }

    private static Timestamp utc(int y, int mo, int d) {
        return DifferentialSupport.utc(y, mo, d);
    }

    private static Timestamp infinity() {
        return DifferentialSupport.infinity();
    }

    private static String loadXml() {
        StringBuilder xml = new StringBuilder();
        try (InputStream in = RefreshTest.class.getResourceAsStream("/reladomo/models/DiffBalance.xml");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                xml.append(line).append('\n');
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not load DiffBalance.xml", e);
        }
        return xml.toString();
    }
}
