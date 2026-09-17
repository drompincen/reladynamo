package io.reladynamo.ddb.differential.findermatrix;

import io.reladynamo.core.temporal.TemporalEncoder;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent storage check of codec-written V rows: cardinality, types, bytes, and H2
 * finder snapshots equal the manifest including primitive null flags.
 */
class FinderValueStorageVerificationTest {

    private static FinderMatrixHarness harness;

    @BeforeAll
    static void setUp() {
        harness = FinderMatrixHarness.boot();
        List<ValueRow> seed = FixtureManifests.coreSeed();
        harness.truncateH2();
        harness.seedH2(seed);
        harness.seedDdb(seed);
        harness.awaitGsi(1, FixtureManifests.V());
        harness.awaitGsi(2, FixtureManifests.X());
        harness.awaitGsi(6, FixtureManifests.F());
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void should_write_verified_wire_forms_for_v_rows() {
        assertWire(FixtureManifests.v(1), itemOf(FixtureManifests.v(1)));
        assertWire(FixtureManifests.v(2), itemOf(FixtureManifests.v(2)));
        assertWire(FixtureManifests.v(5), itemOf(FixtureManifests.v(5)));
        Map<String, AttributeValue> row3 = itemOf(FixtureManifests.v(3));
        assertThat(row3.get("pk").s()).isEqualTo("v1#DIFFFINDERVALUE#1#3");
        assertThat(row3.get("sk").s()).isEqualTo("v1#ND");
        assertThat(row3.get(ItemCodec.SCHEMA_VERSION_ATTR).n()).isEqualTo("1");
        assertThat(row3.get("gsi_bucketId").s()).isEqualTo("v1#GSI#BUCKETID#1");
    }

    @Test
    void should_match_h2_finder_snapshot_to_manifest_including_null_flags() {
        harness.clearCold();
        InvocationResult h2 = harness.run(
                FinderMatrixHarness.Backend.H2,
                () -> DiffFinderValueFinder.bucketId().eq(1),
                FinderShape.findManyQuery());
        List<TypedSnapshot> expected = FinderMatrixHarness.snapshotsOf(FixtureManifests.V());
        assertThat(h2.rows).hasSize(7);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(expected.get(i).values.keySet())
                    .containsExactlyElementsOf(FixtureManifests.mappedAttributeNames());
        }
        assertThat(FinderMatrixHarness.sameMultiset(h2.rows, expected))
                .as("H2 snapshots %s vs manifest %s", h2.rows, expected)
                .isTrue();
        TypedSnapshot nullRow = findRow(h2.rows, 1, 5);
        assertThat(nullRow.get("intValue")).isNull();
        assertThat(nullRow.get("booleanValue")).isNull();
        assertThat(nullRow.get("textValue")).isNull();
        assertThat(nullRow.get("bytesValue")).isNull();
        TypedSnapshot empty = findRow(h2.rows, 1, 2);
        assertThat(empty.get("textValue")).isEqualTo("");
        assertThat(empty.get("intValue")).isEqualTo(Integer.valueOf(0));
        assertThat(empty.get("booleanValue")).isEqualTo(Boolean.FALSE);
        assertThat((byte[]) empty.get("bytesValue")).isEmpty();
    }

    private static Map<String, AttributeValue> itemOf(ValueRow row) {
        Map<String, AttributeValue> item = harness.getItemRaw(row);
        assertThat(item).as("missing DDB item for %s/%s", Integer.valueOf(row.scopeId),
                Integer.valueOf(row.rowId)).isNotEmpty();
        return item;
    }

    private static void assertWire(ValueRow row, Map<String, AttributeValue> item) {
        assertThat(item.get(ItemCodec.SCHEMA_VERSION_ATTR).n()).isEqualTo("1");
        if (row.intValue == null) {
            assertNullAttr(item, "INT_VALUE");
            assertNullAttr(item, "LONG_VALUE");
            assertNullAttr(item, "DOUBLE_VALUE");
            assertNullAttr(item, "FLOAT_VALUE");
            assertNullAttr(item, "DECIMAL_VALUE");
            assertNullAttr(item, "TEXT_VALUE");
            assertNullAttr(item, "BOOLEAN_VALUE");
            assertNullAttr(item, "TIMESTAMP_VALUE");
            assertNullAttr(item, "BYTES_VALUE");
            return;
        }
        assertThat(item.get("INT_VALUE").n()).isEqualTo(Integer.toString(row.intValue.intValue()));
        assertThat(item.get("LONG_VALUE").n()).isEqualTo(Long.toString(row.longValue.longValue()));
        assertIeee(item.get("DOUBLE_VALUE"), 8, Double.doubleToRawLongBits(row.doubleValue.doubleValue()));
        assertIeeeFloat(item.get("FLOAT_VALUE"), row.floatValue.floatValue());
        assertThat(item.get("DECIMAL_VALUE").s()).isEqualTo(row.decimalValue.toPlainString());
        assertThat(item.get("TEXT_VALUE").s()).isEqualTo(row.textValue);
        assertThat(item.get("BOOLEAN_VALUE").bool()).isEqualTo(row.booleanValue);
        assertThat(item.get("TIMESTAMP_VALUE").s()).isEqualTo(TemporalEncoder.encode(row.timestampValue));
        assertThat(item.get("BYTES_VALUE").b().asByteArray()).containsExactly(row.bytesValue);
    }

    private static void assertNullAttr(Map<String, AttributeValue> item, String name) {
        AttributeValue av = item.get(name);
        assertThat(av).as(name).isNotNull();
        assertThat(av.nul()).as(name + " must be NULL=true, not missing").isTrue();
    }

    private static void assertIeee(AttributeValue av, int width, long bits) {
        assertThat(av.b()).isNotNull();
        byte[] bytes = av.b().asByteArray();
        assertThat(bytes).hasSize(width);
        long actual = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong();
        assertThat(actual).isEqualTo(bits);
    }

    private static void assertIeeeFloat(AttributeValue av, float value) {
        assertThat(av.b()).isNotNull();
        byte[] bytes = av.b().asByteArray();
        assertThat(bytes).hasSize(4);
        int actual = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt();
        assertThat(actual).isEqualTo(Float.floatToRawIntBits(value));
    }

    private static TypedSnapshot findRow(List<TypedSnapshot> rows, int scopeId, int rowId) {
        for (int i = 0; i < rows.size(); i++) {
            TypedSnapshot row = rows.get(i);
            if (row.scopeId() == scopeId && row.rowId() == rowId) {
                return row;
            }
        }
        throw new AssertionError("missing snapshot " + scopeId + "/" + rowId + " in " + rows);
    }
}
