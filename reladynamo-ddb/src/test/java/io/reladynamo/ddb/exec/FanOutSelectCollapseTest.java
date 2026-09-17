package io.reladynamo.ddb.exec;

import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.plan.ExpressionValue;
import io.reladynamo.core.plan.FilterExpression;
import io.reladynamo.core.plan.KeyCondition;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.ResidualPredicate;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-06: collapsing a QUERY_FAN_OUT by filter <em>text</em> alone changes OR-branch semantics
 * when children share placeholders but not bindings, names, or residuals.
 */
class FanOutSelectCollapseTest {

    private static LocalDynamoDb ddb;

    @BeforeAll
    static void startLocal() {
        ddb = LocalDynamoDb.start();
    }

    @AfterAll
    static void stopLocal() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void should_return_both_rows_when_or_branches_differ_only_in_literal_value() {
        EntityMapping mapping = ExecFixtures.customerMapping("FanOutLiteralKeep");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(1L, "A", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(2L, "B", "OPEN"));

        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(queryWithNameEquals(mapping, 1L, "A"));
        children.add(queryWithNameEquals(mapping, 2L, "B"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        List<Map<String, Object>> rows = executor.execute(fanOut(mapping, children));

        assertThat(namesOf(rows))
                .as("(id=1 AND name=A) OR (id=2 AND name=B) must keep both rows; collapsing to "
                        + "pk IN (1,2) AND name=A loses row 2")
                .containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void should_not_admit_sibling_literal_when_or_branch_value_differs() {
        EntityMapping mapping = ExecFixtures.customerMapping("FanOutLiteralAdmit");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(1L, "A", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(2L, "A", "OPEN"));

        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(queryWithNameEquals(mapping, 1L, "A"));
        children.add(queryWithNameEquals(mapping, 2L, "B"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        List<Map<String, Object>> rows = executor.execute(fanOut(mapping, children));

        assertThat(idsOf(rows))
                .as("id=2 with name=A matches the first branch's filter text/bindings, not the "
                        + "second branch (name=B); a collapsed LABEL=A would admit the wrong row 2")
                .containsExactly(1L);
    }

    @Test
    void should_return_rows_matching_each_time_range_when_or_bounds_differ() {
        EntityMapping mapping = ExecFixtures.customerMapping("FanOutTimeRange");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);

        String pk1 = ExecFixtures.encodedPk(mapping, 1L);
        String pk2 = ExecFixtures.encodedPk(mapping, 2L);
        String inFirst = "v1#ND#20200615";
        String inSecond = "v1#ND#20220615";
        putRaw(mapping, codec, pk1, inFirst, 1L, "in-2020");
        putRaw(mapping, codec, pk2, inSecond, 2L, "in-2022");
        putRaw(mapping, codec, pk2, inFirst, 2L, "wrong-admit-2020-on-pk2");

        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(queryWithSkBetween(mapping, pk1, "v1#ND#20200101", "v1#ND#20201231"));
        children.add(queryWithSkBetween(mapping, pk2, "v1#ND#20220101", "v1#ND#20221231"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        List<Map<String, Object>> rows = executor.execute(fanOut(mapping, children));

        assertThat(namesOf(rows))
                .as("equal-looking BETWEEN expressions with different bounds must not share the "
                        + "first child's dates")
                .containsExactlyInAnyOrder("in-2020", "in-2022");
    }

    @Test
    void should_not_apply_first_branch_attribute_when_name_mappings_differ() {
        EntityMapping mapping = ExecFixtures.customerMapping("FanOutNameMap");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(1L, "Ada", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(2L, "Bob", "Ada"));

        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(queryWithPlaceholder(mapping, 1L, "#ATTR", "name", ":v0", "Ada"));
        children.add(queryWithPlaceholder(mapping, 2L, "#ATTR", "status", ":v0", "Ada"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        List<Map<String, Object>> rows = executor.execute(fanOut(mapping, children));

        assertThat(namesOf(rows))
                .as("same filter text #ATTR = :v0 with different name maps (name vs status) must "
                        + "not collapse onto the first child's attribute")
                .containsExactlyInAnyOrder("Ada", "Bob");
    }

    @Test
    void should_apply_each_branch_residual_when_residuals_differ() {
        EntityMapping mapping = ExecFixtures.customerMapping("FanOutResidual");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(1L, "Ada", "OPEN"));
        ExecFixtures.putCustomer(ddb.client(), mapping, codec, ExecFixtures.customer(2L, "Bob", "OPEN"));

        List<QueryPlan> children = new ArrayList<QueryPlan>();
        children.add(queryWithResidual(mapping, 1L, "Ada"));
        children.add(queryWithResidual(mapping, 2L, "Bob"));

        QueryPlanExecutor executor = new QueryPlanExecutor(ddb.client(), codec);
        List<Map<String, Object>> rows = executor.execute(fanOut(mapping, children));

        assertThat(namesOf(rows))
                .as("picking one child's residual for the collapsed IN query drops the other branch")
                .containsExactlyInAnyOrder("Ada", "Bob");
    }

    @Test
    void should_collapse_identical_predicate_fan_out_into_one_request() {
        EntityMapping mapping = ExecFixtures.customerMapping("FanOutSafeCollapse");
        new TableCreator(ddb.client()).create(mapping);
        ItemCodec codec = new ItemCodec(mapping);
        int partitions = 8;
        for (int i = 0; i < partitions; i++) {
            ExecFixtures.putCustomer(ddb.client(), mapping, codec,
                    ExecFixtures.customer((long) (i + 1), "n" + i, "OPEN"));
        }

        List<QueryPlan> children = new ArrayList<QueryPlan>();
        for (int i = 0; i < partitions; i++) {
            children.add(queryWithStatusEquals(mapping, (long) (i + 1), "OPEN"));
        }

        ExecFixtures.CountingClient counting = new ExecFixtures.CountingClient(ddb.client());
        QueryPlanExecutor executor = new QueryPlanExecutor(counting.client, codec);
        List<Map<String, Object>> rows = executor.execute(fanOut(mapping, children));

        assertThat(namesOf(rows))
                .containsExactlyInAnyOrder("n0", "n1", "n2", "n3", "n4", "n5", "n6", "n7");
        assertThat(counting.reads.get())
                .as("identical filter+names+values+residual must still collapse; N requests is a regression")
                .isEqualTo(1);
        assertThat(executor.lastExplain().requestCount())
                .as("one PartiQL IN ExecuteStatement for 8 same-shape partitions")
                .isEqualTo(1);
    }

    private static QueryPlan fanOut(EntityMapping mapping, List<QueryPlan> children) {
        return ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY_FAN_OUT)
                .fanOut(children)
                .build();
    }

    private static QueryPlan queryWithNameEquals(EntityMapping mapping, long id, String name) {
        return queryWithPlaceholder(mapping, id, "#LABEL", "name", ":v0", name);
    }

    private static QueryPlan queryWithStatusEquals(EntityMapping mapping, long id, String status) {
        return queryWithPlaceholder(mapping, id, "#STATUS", "status", ":v0", status);
    }

    private static QueryPlan queryWithPlaceholder(EntityMapping mapping, long id,
                                                  String placeholder, String itemName,
                                                  String token, String value) {
        Map<String, String> names = new LinkedHashMap<String, String>();
        names.put(placeholder, itemName);
        Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        values.put(token, ExpressionValue.s(value));
        return ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", ExecFixtures.encodedPk(mapping, id)))
                .filterExpression(new FilterExpression(placeholder + " = " + token, names, values))
                .build();
    }

    private static QueryPlan queryWithSkBetween(EntityMapping mapping, String encodedPk,
                                                String lo, String hi) {
        return ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionAndBetween("pk", "sk", encodedPk, lo, hi))
                .build();
    }

    private static QueryPlan queryWithResidual(EntityMapping mapping, long id, String name) {
        return ExecFixtures.basePlan(mapping)
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", ExecFixtures.encodedPk(mapping, id)))
                .residual(new ResidualPredicate(ExecFixtures.residualNameEquals(name)))
                .build();
    }

    private static void putRaw(EntityMapping mapping, ItemCodec codec,
                               String pk, String sk, long id, String name) {
        Map<String, Object> row = ExecFixtures.customer(id, name, "OPEN");
        Map<String, AttributeValue> item = new LinkedHashMap<String, AttributeValue>(codec.encode(row));
        item.put("pk", AttributeValue.builder().s(pk).build());
        item.put("sk", AttributeValue.builder().s(sk).build());
        ddb.client().putItem(PutItemRequest.builder().tableName(mapping.tableName()).item(item).build());
    }

    private static List<Object> namesOf(List<Map<String, Object>> rows) {
        List<Object> names = new ArrayList<Object>();
        for (int i = 0; i < rows.size(); i++) {
            names.add(rows.get(i).get("name"));
        }
        return names;
    }

    private static List<Object> idsOf(List<Map<String, Object>> rows) {
        List<Object> ids = new ArrayList<Object>();
        for (int i = 0; i < rows.size(); i++) {
            ids.add(rows.get(i).get("id"));
        }
        return ids;
    }
}
