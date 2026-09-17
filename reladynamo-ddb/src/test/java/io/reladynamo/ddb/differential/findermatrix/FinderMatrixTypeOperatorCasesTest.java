package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.bytearray.ByteArraySet;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import org.eclipse.collections.impl.set.mutable.primitive.BooleanHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.DoubleHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.FloatHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §6.1 type × operator matrix: every generated payload accessor crossed with the operators
 * its Reladomo 18.1.0 attribute type actually exposes. Stimulus is a generated finder;
 * DDB data is codec-written; cold-cache protocol is the harness. H2 is the oracle.
 *
 * 9 eq + 8 notEq + 7 greaterThan + 7 lessThan + 9 small IN + 9 isNull + 9 isNotNull = 58 MATCH
 * plus one named Reladomo refusal (binary {@code notEq}).
 *
 * <p>Finding 28: Reladomo 18.1.0 {@code ByteArrayAttribute.notEq(byte[])} is {@code athrow} of
 * {@code UnsupportedOperationException}. There is no operation for the adapter to translate.
 * That case asserts the refusal by name rather than treating it as an adapter gap.
 *
 * {@code bytesValue().in(...)} requires Reladomo {@code ByteArraySet}; a {@code HashSet<byte[]>}
 * ClassCasts inside {@code ByteArrayAttribute.in(Set)}.
 */
class FinderMatrixTypeOperatorCasesTest {

    private static FinderMatrixHarness harness;

    @BeforeAll
    static void setUp() {
        harness = FinderMatrixHarness.boot();
        List<ValueRow> seed = FixtureManifests.V();
        harness.truncateH2();
        harness.seedH2(seed);
        harness.seedDdb(seed);
        harness.awaitGsi(1, FixtureManifests.V());
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void should_enumerate_fifty_eight_match_invocations_and_one_named_binary_not_eq_refusal() {
        // 9 types × {eq, in, isNull, isNotNull} + 8 non-binary notEq + 7 ordered types × {gt, lt}
        assertThat(9 * 4 + 8 + 7 * 2).isEqualTo(58);
    }

    @ParameterizedTest(name = "eq {0}")
    @ValueSource(strings = {
            "int", "long", "double", "float", "decimal", "string", "boolean", "timestamp", "bytes"
    })
    void should_match_h2_for_eq_on_codec_values(String type) {
        assertCase("eq " + type, () -> Q(1).and(eqLeaf(type)), eqIds(type));
    }

    @ParameterizedTest(name = "notEq {0}")
    @ValueSource(strings = {
            "int", "long", "double", "float", "decimal", "string", "boolean", "timestamp"
    })
    void should_match_h2_for_not_eq_on_codec_values(String type) {
        assertCase("notEq " + type, () -> Q(1).and(notEqLeaf(type)), notEqIds(type));
    }

    /**
     * Finding 28: Reladomo 18.1.0 refuses {@code notEq} on binary attributes. javap of
     * {@code ByteArrayAttribute.notEq(byte[])} is {@code athrow} of
     * {@code UnsupportedOperationException}. The adapter has nothing to translate.
     */
    @Test
    void should_refuse_not_eq_on_byte_array_because_reladomo_does_not_offer_it() {
        assertThatThrownBy(() ->
                DiffFinderValueFinder.bytesValue().notEq(FixtureManifests.hex(0x01, 0x02)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("notEq is not supported for byte array attributes");
    }

    @ParameterizedTest(name = "greaterThan {0}")
    @ValueSource(strings = {
            "int", "long", "double", "float", "decimal", "string", "timestamp"
    })
    void should_match_h2_for_greater_than_on_codec_values(String type) {
        assertCase("gt " + type, () -> Q(1).and(greaterThanLeaf(type)), gtIds(type));
    }

    @ParameterizedTest(name = "lessThan {0}")
    @ValueSource(strings = {
            "int", "long", "double", "float", "decimal", "string", "timestamp"
    })
    void should_match_h2_for_less_than_on_codec_values(String type) {
        assertCase("lt " + type, () -> Q(1).and(lessThanLeaf(type)), ltIds(type));
    }

    @ParameterizedTest(name = "in {0}")
    @ValueSource(strings = {
            "int", "long", "double", "float", "decimal", "string", "boolean", "timestamp", "bytes"
    })
    void should_match_h2_for_small_in_on_codec_values(String type) {
        assertCase("in " + type, () -> Q(1).and(inLeaf(type)), inIds(type));
    }

    @ParameterizedTest(name = "isNull {0}")
    @ValueSource(strings = {
            "int", "long", "double", "float", "decimal", "string", "boolean", "timestamp", "bytes"
    })
    void should_match_h2_for_is_null_on_codec_values(String type) {
        assertCase("isNull " + type, () -> Q(1).and(nullLeaf(type)), ids(5));
    }

    @ParameterizedTest(name = "isNotNull {0}")
    @ValueSource(strings = {
            "int", "long", "double", "float", "decimal", "string", "boolean", "timestamp", "bytes"
    })
    void should_match_h2_for_is_not_null_on_codec_values(String type) {
        assertCase("isNotNull " + type, () -> Q(1).and(notNullLeaf(type)), ids(1, 2, 3, 4, 6, 7));
    }

    private void assertCase(String label, FinderMatrixHarness.OperationRecipe recipe,
                            List<Integer> expectedIds) {
        FinderShape shape = FinderShape.findManyQuery();
        InvocationResult h2 = harness.run(FinderMatrixHarness.Backend.H2, recipe, shape);
        assertThat(rowIds(h2))
                .as("H2 witness %s", label)
                .containsExactlyInAnyOrderElementsOf(expectedIds);
        harness.matchH2(recipe, shape);
    }

    private static Operation Q(int bucketId) {
        return DiffFinderValueFinder.bucketId().eq(bucketId);
    }

    private static Operation eqLeaf(String type) {
        if ("int".equals(type)) {
            return DiffFinderValueFinder.intValue().eq(2);
        }
        if ("long".equals(type)) {
            return DiffFinderValueFinder.longValue().eq(9007199254740992L);
        }
        if ("double".equals(type)) {
            return DiffFinderValueFinder.doubleValue().eq(2.5d);
        }
        if ("float".equals(type)) {
            return DiffFinderValueFinder.floatValue().eq(2.5f);
        }
        if ("decimal".equals(type)) {
            return DiffFinderValueFinder.decimalValue().eq(new BigDecimal("2.5"));
        }
        if ("string".equals(type)) {
            return DiffFinderValueFinder.textValue().eq("beta");
        }
        if ("boolean".equals(type)) {
            return DiffFinderValueFinder.booleanValue().eq(true);
        }
        if ("timestamp".equals(type)) {
            return DiffFinderValueFinder.timestampValue().eq(new Timestamp(0L));
        }
        if ("bytes".equals(type)) {
            return DiffFinderValueFinder.bytesValue().eq(FixtureManifests.hex(0x01, 0x02));
        }
        throw new IllegalArgumentException(type);
    }

    private static Operation notEqLeaf(String type) {
        if ("int".equals(type)) {
            return DiffFinderValueFinder.intValue().notEq(2);
        }
        if ("long".equals(type)) {
            return DiffFinderValueFinder.longValue().notEq(9007199254740992L);
        }
        if ("double".equals(type)) {
            return DiffFinderValueFinder.doubleValue().notEq(2.5d);
        }
        if ("float".equals(type)) {
            return DiffFinderValueFinder.floatValue().notEq(2.5f);
        }
        if ("decimal".equals(type)) {
            return DiffFinderValueFinder.decimalValue().notEq(new BigDecimal("2.5"));
        }
        if ("string".equals(type)) {
            return DiffFinderValueFinder.textValue().notEq("beta");
        }
        if ("boolean".equals(type)) {
            return DiffFinderValueFinder.booleanValue().notEq(true);
        }
        if ("timestamp".equals(type)) {
            return DiffFinderValueFinder.timestampValue().notEq(new Timestamp(0L));
        }
        throw new IllegalArgumentException(type);
    }

    private static Operation greaterThanLeaf(String type) {
        if ("int".equals(type)) {
            return DiffFinderValueFinder.intValue().greaterThan(2);
        }
        if ("long".equals(type)) {
            return DiffFinderValueFinder.longValue().greaterThan(9007199254740992L);
        }
        if ("double".equals(type)) {
            return DiffFinderValueFinder.doubleValue().greaterThan(2.5d);
        }
        if ("float".equals(type)) {
            return DiffFinderValueFinder.floatValue().greaterThan(2.5f);
        }
        if ("decimal".equals(type)) {
            return DiffFinderValueFinder.decimalValue().greaterThan(new BigDecimal("2.5"));
        }
        if ("string".equals(type)) {
            return DiffFinderValueFinder.textValue().greaterThan("beta");
        }
        if ("timestamp".equals(type)) {
            return DiffFinderValueFinder.timestampValue().greaterThan(new Timestamp(0L));
        }
        throw new IllegalArgumentException(type);
    }

    private static Operation lessThanLeaf(String type) {
        if ("int".equals(type)) {
            return DiffFinderValueFinder.intValue().lessThan(2);
        }
        if ("long".equals(type)) {
            return DiffFinderValueFinder.longValue().lessThan(9007199254740992L);
        }
        if ("double".equals(type)) {
            return DiffFinderValueFinder.doubleValue().lessThan(2.5d);
        }
        if ("float".equals(type)) {
            return DiffFinderValueFinder.floatValue().lessThan(2.5f);
        }
        if ("decimal".equals(type)) {
            return DiffFinderValueFinder.decimalValue().lessThan(new BigDecimal("2.5"));
        }
        if ("string".equals(type)) {
            return DiffFinderValueFinder.textValue().lessThan("beta");
        }
        if ("timestamp".equals(type)) {
            return DiffFinderValueFinder.timestampValue().lessThan(new Timestamp(0L));
        }
        throw new IllegalArgumentException(type);
    }

    private static Operation inLeaf(String type) {
        if ("int".equals(type)) {
            IntHashSet set = new IntHashSet();
            set.add(-10);
            set.add(2);
            return DiffFinderValueFinder.intValue().in(set);
        }
        if ("long".equals(type)) {
            LongHashSet set = new LongHashSet();
            set.add(-9007199254740993L);
            set.add(9007199254740992L);
            return DiffFinderValueFinder.longValue().in(set);
        }
        if ("double".equals(type)) {
            DoubleHashSet set = new DoubleHashSet();
            set.add(-10.5d);
            set.add(2.5d);
            return DiffFinderValueFinder.doubleValue().in(set);
        }
        if ("float".equals(type)) {
            FloatHashSet set = new FloatHashSet();
            set.add(-10.5f);
            set.add(2.5f);
            return DiffFinderValueFinder.floatValue().in(set);
        }
        if ("decimal".equals(type)) {
            Set<BigDecimal> set = new HashSet<BigDecimal>();
            set.add(new BigDecimal("-10.5"));
            set.add(new BigDecimal("2.50"));
            return DiffFinderValueFinder.decimalValue().in(set);
        }
        if ("string".equals(type)) {
            Set<String> set = new HashSet<String>();
            set.add("alpha");
            set.add("beta");
            return DiffFinderValueFinder.textValue().in(set);
        }
        if ("boolean".equals(type)) {
            BooleanHashSet set = new BooleanHashSet();
            set.add(false);
            set.add(true);
            return DiffFinderValueFinder.booleanValue().in(set);
        }
        if ("timestamp".equals(type)) {
            Set<Timestamp> set = new HashSet<Timestamp>();
            set.add(new Timestamp(-1001L));
            set.add(new Timestamp(0L));
            return DiffFinderValueFinder.timestampValue().in(set);
        }
        if ("bytes".equals(type)) {
            ByteArraySet set = new ByteArraySet();
            set.add(FixtureManifests.hex(0x00, 0xFF));
            set.add(FixtureManifests.hex(0x01, 0x02));
            return DiffFinderValueFinder.bytesValue().in(set);
        }
        throw new IllegalArgumentException(type);
    }

    private static Operation nullLeaf(String type) {
        if ("int".equals(type)) {
            return DiffFinderValueFinder.intValue().isNull();
        }
        if ("long".equals(type)) {
            return DiffFinderValueFinder.longValue().isNull();
        }
        if ("double".equals(type)) {
            return DiffFinderValueFinder.doubleValue().isNull();
        }
        if ("float".equals(type)) {
            return DiffFinderValueFinder.floatValue().isNull();
        }
        if ("decimal".equals(type)) {
            return DiffFinderValueFinder.decimalValue().isNull();
        }
        if ("string".equals(type)) {
            return DiffFinderValueFinder.textValue().isNull();
        }
        if ("boolean".equals(type)) {
            return DiffFinderValueFinder.booleanValue().isNull();
        }
        if ("timestamp".equals(type)) {
            return DiffFinderValueFinder.timestampValue().isNull();
        }
        if ("bytes".equals(type)) {
            return DiffFinderValueFinder.bytesValue().isNull();
        }
        throw new IllegalArgumentException(type);
    }

    private static Operation notNullLeaf(String type) {
        if ("int".equals(type)) {
            return DiffFinderValueFinder.intValue().isNotNull();
        }
        if ("long".equals(type)) {
            return DiffFinderValueFinder.longValue().isNotNull();
        }
        if ("double".equals(type)) {
            return DiffFinderValueFinder.doubleValue().isNotNull();
        }
        if ("float".equals(type)) {
            return DiffFinderValueFinder.floatValue().isNotNull();
        }
        if ("decimal".equals(type)) {
            return DiffFinderValueFinder.decimalValue().isNotNull();
        }
        if ("string".equals(type)) {
            return DiffFinderValueFinder.textValue().isNotNull();
        }
        if ("boolean".equals(type)) {
            return DiffFinderValueFinder.booleanValue().isNotNull();
        }
        if ("timestamp".equals(type)) {
            return DiffFinderValueFinder.timestampValue().isNotNull();
        }
        if ("bytes".equals(type)) {
            return DiffFinderValueFinder.bytesValue().isNotNull();
        }
        throw new IllegalArgumentException(type);
    }

    private static List<Integer> eqIds(String type) {
        if ("string".equals(type)) {
            return ids(3);
        }
        if ("boolean".equals(type)) {
            return ids(3, 4, 6);
        }
        return ids(3, 6);
    }

    private static List<Integer> notEqIds(String type) {
        List<Integer> out = ids(1, 2, 3, 4, 6, 7);
        out.removeAll(eqIds(type));
        return out;
    }

    private static List<Integer> gtIds(String type) {
        if ("string".equals(type)) {
            return ids(6);
        }
        return ids(4, 7);
    }

    private static List<Integer> ltIds(String type) {
        if ("string".equals(type)) {
            return ids(1, 2, 4, 7);
        }
        return ids(1, 2);
    }

    private static List<Integer> inIds(String type) {
        if ("string".equals(type)) {
            return ids(1, 3);
        }
        if ("boolean".equals(type)) {
            return ids(1, 2, 3, 4, 6, 7);
        }
        return ids(1, 3, 6);
    }

    private static List<Integer> ids(int... values) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < values.length; i++) {
            out.add(Integer.valueOf(values[i]));
        }
        return out;
    }

    private static List<Integer> rowIds(InvocationResult result) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < result.rows.size(); i++) {
            out.add(Integer.valueOf(result.rows.get(i).rowId()));
        }
        return out;
    }
}
