package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.ExpressionValue;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffNumeric;
import io.reladynamo.ddb.differential.domain.DiffNumericFinder;
import io.reladynamo.ddb.differential.domain.DiffNumericList;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.eclipse.collections.impl.set.mutable.primitive.DoubleHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.FloatHashSet;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-04 through generated finders on codec-written rows.
 *
 * <p>The codec stores float/double as IEEE-754 {@code B} and BigDecimal as decimal {@code S}.
 * DynamoDB {@code N} filters (and raw {@code B} / decimal-string order) are not the Reladomo
 * numeric predicates, so the planner leaves those leaves as a typed residual after decode.
 */
class NumericPredicateFinderTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping mapping;
    private static ItemCodec codec;
    private static DynamoDbWriter writer;
    private static DynamoDbPersister adapter;
    private static QueryPlanner planner;
    private static PhysicalDesign design;
    private static MithraObjectReader jdbcReader;
    private static MithraAbstractObjectPortal portal;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();

        mapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffNumeric.xml"));
        codec = new ItemCodec(mapping);
        DifferentialSupport.createPkSkTable(ddb, mapping.tableName());

        writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        planner = new QueryPlanner();
        design = PhysicalDesign.builder(mapping).build();
        adapter = new DynamoDbPersister(
                DiffNumericFinder.getFinderInstance(), mapping, writer,
                planner, new QueryPlanExecutor(ddb.client(), codec),
                design, PlannerConfig.builder().build());

        portal = (MithraAbstractObjectPortal) DiffNumericFinder.getMithraObjectPortal();
        jdbcReader = portal.getDatabaseObject();

        seedRows();
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
    void should_leave_float_double_and_decimal_predicates_as_residual_not_dynamo_n() {
        assertNumericIsResidual(DiffNumericFinder.id().eq(1)
                .and(DiffNumericFinder.quantity().eq(1.5d)), "quantity");
        assertNumericIsResidual(DiffNumericFinder.id().eq(1)
                .and(DiffNumericFinder.rate().eq(1.5f)), "rate");
        assertNumericIsResidual(DiffNumericFinder.id().eq(1)
                .and(DiffNumericFinder.amount().eq(new BigDecimal("1.1"))), "amount");
        assertNumericIsResidual(DiffNumericFinder.id().eq(1)
                .and(DiffNumericFinder.quantity().greaterThan(-2.0d)), "quantity");
        assertNumericIsResidual(DiffNumericFinder.id().eq(1)
                .and(DiffNumericFinder.quantity().in(doubleSet(-0.0d, 1.5d))), "quantity");
    }

    @Test
    void should_match_codec_written_double_equality_including_negatives_and_zero() {
        assertIds(DiffNumericFinder.id().eq(10).and(DiffNumericFinder.quantity().eq(1.5d)), 10);
        assertIds(DiffNumericFinder.id().eq(11).and(DiffNumericFinder.quantity().eq(-4.0d)), 11);
        assertIds(DiffNumericFinder.id().eq(12).and(DiffNumericFinder.quantity().eq(0.0d)), 12);
        assertIds(DiffNumericFinder.id().eq(10).and(DiffNumericFinder.quantity().eq(9.0d)));
    }

    @Test
    void should_apply_each_double_range_operator_and_between() {
        Operation keyed = allIds();
        assertIds(keyed.and(DiffNumericFinder.quantity().greaterThan(0.0d)), 10, 14, 15);
        assertIds(keyed.and(DiffNumericFinder.quantity().greaterThanEquals(1.5d)), 10, 14, 15);
        assertIds(keyed.and(DiffNumericFinder.quantity().lessThan(0.0d)), 11, 16);
        assertIds(keyed.and(DiffNumericFinder.quantity().lessThanEquals(0.0d)), 11, 12, 13, 16);
        assertIds(keyed.and(DiffNumericFinder.quantity().greaterThanEquals(-4.0d)
                .and(DiffNumericFinder.quantity().lessThanEquals(1.5d))), 10, 11, 12, 13);
    }

    @Test
    void should_apply_double_in_per_element() {
        assertIds(allIds().and(DiffNumericFinder.quantity().in(doubleSet(-4.0d, 1.5d))), 10, 11);
    }

    @Test
    void should_treat_negative_zero_as_equal_to_positive_zero() {
        assertIds(DiffNumericFinder.id().eq(13).and(DiffNumericFinder.quantity().eq(0.0d)), 13);
        assertIds(DiffNumericFinder.id().eq(12).and(DiffNumericFinder.quantity().eq(-0.0d)), 12);
        assertThat(Double.doubleToRawLongBits(-0.0d)).isNotEqualTo(Double.doubleToRawLongBits(0.0d));
    }

    @Test
    void should_not_match_nan_on_equality_and_should_match_infinities() {
        assertIds(DiffNumericFinder.id().eq(17).and(DiffNumericFinder.quantity().eq(Double.NaN)));
        assertIds(DiffNumericFinder.id().eq(14)
                .and(DiffNumericFinder.quantity().eq(Double.POSITIVE_INFINITY)), 14);
        assertIds(DiffNumericFinder.id().eq(16)
                .and(DiffNumericFinder.quantity().eq(Double.NEGATIVE_INFINITY)), 16);
        assertIds(allIds().and(DiffNumericFinder.quantity().greaterThan(0.0d)), 10, 14, 15);
        assertIds(DiffNumericFinder.id().eq(17)
                .and(DiffNumericFinder.quantity().greaterThan(0.0d)));
    }

    @Test
    void should_match_float_equality_ranges_in_negative_zero_and_specials() {
        assertIds(DiffNumericFinder.id().eq(10).and(DiffNumericFinder.rate().eq(1.5f)), 10);
        assertIds(DiffNumericFinder.id().eq(11).and(DiffNumericFinder.rate().eq(-4.0f)), 11);
        assertIds(DiffNumericFinder.id().eq(13).and(DiffNumericFinder.rate().eq(0.0f)), 13);
        assertIds(allIds().and(DiffNumericFinder.rate().greaterThan(0.0f)), 10, 14, 15);
        assertIds(allIds().and(DiffNumericFinder.rate().lessThan(0.0f)), 11, 16);
        assertIds(allIds().and(DiffNumericFinder.rate().in(floatSet(-4.0f, 1.5f))), 10, 11);
        assertIds(DiffNumericFinder.id().eq(17).and(DiffNumericFinder.rate().eq(Float.NaN)));
        assertIds(DiffNumericFinder.id().eq(14)
                .and(DiffNumericFinder.rate().eq(Float.POSITIVE_INFINITY)), 14);
    }

    @Test
    void should_match_big_decimal_by_numeric_value_not_scale() {
        assertThat(new BigDecimal("1.10").equals(new BigDecimal("1.1")))
                .as("pin that BigDecimal.equals is scale-sensitive, so residual must not use it")
                .isFalse();
        Map<String, AttributeValue> encoded = codec.encode(row(10, 1.5f, 1.5d, new BigDecimal("1.10")));
        assertThat(encoded.get("AMOUNT").s()).isEqualTo("1.10");
        assertThat(encoded.get("AMOUNT").n()).isNull();

        assertIds(DiffNumericFinder.id().eq(10)
                .and(DiffNumericFinder.amount().eq(new BigDecimal("1.1"))), 10);
        assertIds(DiffNumericFinder.id().eq(10)
                .and(DiffNumericFinder.amount().eq(new BigDecimal("1.10"))), 10);
        assertIds(DiffNumericFinder.id().eq(10)
                .and(DiffNumericFinder.amount().eq(new BigDecimal("1.11"))));
    }

    @Test
    void should_apply_big_decimal_range_operators_and_in() {
        Operation keyed = allIds();
        assertIds(keyed.and(DiffNumericFinder.amount().greaterThan(new BigDecimal("0"))), 10, 14, 15, 17);
        assertIds(keyed.and(DiffNumericFinder.amount().greaterThanEquals(new BigDecimal("1.10"))),
                10, 14, 15, 17);
        assertIds(keyed.and(DiffNumericFinder.amount().lessThan(new BigDecimal("0"))), 11, 16);
        assertIds(keyed.and(DiffNumericFinder.amount().lessThanEquals(BigDecimal.ZERO)), 11, 12, 13, 16);
        assertIds(keyed.and(DiffNumericFinder.amount().greaterThanEquals(new BigDecimal("-4.0000"))
                .and(DiffNumericFinder.amount().lessThanEquals(new BigDecimal("1.1")))),
                10, 11, 12, 13);
        Set<BigDecimal> amounts = new HashSet<BigDecimal>();
        amounts.add(new BigDecimal("1.1"));
        amounts.add(new BigDecimal("-4.0000"));
        assertIds(keyed.and(DiffNumericFinder.amount().in(amounts)), 10, 11);
    }

    @Test
    void should_store_float_and_double_as_ieee_binary_not_n() {
        Map<String, AttributeValue> item = codec.encode(row(10, 1.5f, 1.5d, new BigDecimal("1.10")));
        assertThat(item.get("QUANTITY").b()).isNotNull();
        assertThat(item.get("QUANTITY").n()).isNull();
        assertThat(item.get("RATE").b()).isNotNull();
        assertThat(item.get("RATE").n()).isNull();
        assertThat(item.get("AMOUNT").s()).isEqualTo("1.10");
    }

    private static void seedRows() {
        writer.insert(row(10, 1.5f, 1.5d, new BigDecimal("1.10")));
        writer.insert(row(11, -4.0f, -4.0d, new BigDecimal("-4.0000")));
        writer.insert(row(12, 0.0f, 0.0d, new BigDecimal("0.00")));
        writer.insert(row(13, -0.0f, -0.0d, new BigDecimal("0.0")));
        writer.insert(row(14, Float.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, new BigDecimal("10.0000")));
        writer.insert(row(15, 8.0f, 8.0d, new BigDecimal("8.0000")));
        writer.insert(row(16, Float.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, new BigDecimal("-10.0000")));
        writer.insert(row(17, Float.NaN, Double.NaN, new BigDecimal("99.0000")));
    }

    private static void assertNumericIsResidual(Operation op, String javaName) {
        QueryPlan plan = planner.plan(new AnalyzedOperation(op), design);
        String filter = plan.filterExpression();
        if (filter != null) {
            assertThat(filter.toUpperCase())
                    .as("numeric predicates must not become a DynamoDB filter; plan=%s",
                            plan.toAssertableString())
                    .doesNotContain(javaName.toUpperCase());
        }
        for (Map.Entry<String, ExpressionValue> e : plan.expressionAttributeValues().entrySet()) {
            assertThat(e.getValue().kind())
                    .as("Double/Float/BigDecimal must never be bound as N (%s=%s) plan=%s",
                            e.getKey(), e.getValue(), plan.toAssertableString())
                    .isNotEqualTo(ExpressionValue.Kind.N);
        }
        assertThat(plan.residual().isEmpty())
                .as("typed residual must hold the numeric predicate: %s", plan.toAssertableString())
                .isFalse();
        assertThat(plan.residualOperationDump().toLowerCase()).contains(javaName.toLowerCase());
        assertThat(plan.estimatedItemsExamined())
                .as("explain must not claim a wire selectivity the plan no longer has")
                .isGreaterThanOrEqualTo(1);
        assertThat(plan.dynamoLimit())
                .as("residual plans must not Limit server-side")
                .isNull();
    }

    private static void assertIds(Operation op, int... expected) {
        portal.setMithraObjectReader(adapter);
        try {
            DiffNumericList list = DiffNumericFinder.findMany(op);
            list.setBypassCache(true);
            list.forceResolve();
            List<Integer> actual = new ArrayList<Integer>();
            for (int i = 0; i < list.size(); i++) {
                DiffNumeric row = (DiffNumeric) list.get(i);
                actual.add(Integer.valueOf(row.getId()));
            }
            List<Integer> expectedIds = new ArrayList<Integer>();
            for (int i = 0; i < expected.length; i++) {
                expectedIds.add(Integer.valueOf(expected[i]));
            }
            assertThat(actual)
                    .as("op=%s", op)
                    .containsExactlyInAnyOrderElementsOf(expectedIds);
            assertThat(adapter.count(op)).isEqualTo(expected.length);
        } finally {
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    private static Operation allIds() {
        IntHashSet ids = new IntHashSet();
        ids.add(10);
        ids.add(11);
        ids.add(12);
        ids.add(13);
        ids.add(14);
        ids.add(15);
        ids.add(16);
        ids.add(17);
        return DiffNumericFinder.id().in(ids);
    }

    private static DoubleHashSet doubleSet(double... values) {
        DoubleHashSet set = new DoubleHashSet();
        for (int i = 0; i < values.length; i++) {
            set.add(values[i]);
        }
        return set;
    }

    private static FloatHashSet floatSet(float... values) {
        FloatHashSet set = new FloatHashSet();
        for (int i = 0; i < values.length; i++) {
            set.add(values[i]);
        }
        return set;
    }

    private static Map<String, Object> row(int id, float rate, double quantity, BigDecimal amount) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Integer.valueOf(id));
        row.put("rate", Float.valueOf(rate));
        row.put("quantity", Double.valueOf(quantity));
        row.put("amount", amount);
        return row;
    }
}
