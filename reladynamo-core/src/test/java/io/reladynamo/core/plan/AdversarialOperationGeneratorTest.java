package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.gs.fw.common.mithra.finder.All;
import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.AndOperation;
import com.gs.fw.common.mithra.finder.MultiEqualityOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.OrOperation;
import com.gs.fw.common.mithra.finder.asofop.AsOfEqOperation;
import io.reladynamo.core.plan.eval.QueryPlanInterpreter;
import io.reladynamo.core.plan.fixture.PlanRuleData;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;
import io.reladynamo.core.plan.reladomo.ReladomoOperationAccess;
import io.reladynamo.core.temporal.TemporalEncoder;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static io.reladynamo.core.plan.PlanFixtures.INFINITY;
import static io.reladynamo.core.plan.PlanFixtures.planRule;
import static io.reladynamo.core.plan.PlanFixtures.utc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fuzzes Operation trees. The planner must either refuse or produce a plan whose
 * interpreter agrees with Reladomo {@code matches} — never silently fetch the wrong rows.
 */
class AdversarialOperationGeneratorTest {

    static {
        PlanReladomoBoot.ensure();
    }

    private static final Timestamp B0 = utc(2026, java.util.Calendar.JANUARY, 1);
    private static final Timestamp B1 = utc(2026, java.util.Calendar.JUNE, 1);
    private static final Timestamp B2 = utc(2026, java.util.Calendar.DECEMBER, 1);

    @Property(tries = 80)
    void should_never_silently_return_wrong_rows(@ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int seed) {
        assertOracle(seed);
    }

    @Test
    void should_never_silently_return_wrong_rows_named_seeds() {
        int[] seeds = {0, 1, 2, 7, 13, 42, 99, 255, 342, 1024, 99991};
        for (int i = 0; i < seeds.length; i++) {
            assertOracle(seeds[i]);
        }
    }

    @Test
    void should_not_accept_row_when_pk_in_contradicts_equality() {
        IntHashSet ids = new IntHashSet();
        ids.add(2);
        ids.add(3);
        Operation tree = (Operation) PlanRuleFinder.ruleId().in(ids)
                .and(PlanRuleFinder.ruleId().eq(1))
                .and(PlanRuleFinder.processingDate().eq(INFINITY)
                        .or(PlanRuleFinder.ruleId().eq(1)))
                .and(PlanRuleFinder.ruleName().startsWith("r")
                        .or(PlanRuleFinder.ruleId().notEq(99)));
        QueryPlan plan = new QueryPlanner().plan(new AnalyzedOperation(tree), planRule());
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        PlanRuleData data = rule(1, B0, B2, B0, INFINITY, true);
        Operation analyzed = new AnalyzedOperation(tree).getAnalyzedOperation();
        boolean expected = reladomoSqlPass(analyzed, data);
        boolean actual = interp.accepts(plan, itemOf(data), data);
        assertThat(expected).as("Reladomo: IN(2,3) AND eq(1) is a contradiction").isFalse();
        assertThat(actual).as("planner must not return a row Reladomo rejects").isFalse();
    }

    @Test
    void should_refuse_or_plan_business_date_only_without_sk_range() {
        Operation op = (Operation) PlanRuleFinder.ruleId().eq(1)
                .and(PlanRuleFinder.businessDate().eq(B1));
        QueryPlan plan = new QueryPlanner().plan(new AnalyzedOperation(op), planRule());
        assertThat(plan.keyConditionExpression()).doesNotContain("#B#");
        assertThat(plan.kind()).isIn(PlanKind.QUERY, PlanKind.GET_ITEM);
    }

    @Test
    void should_cover_asof_boundary_instants() {
        Timestamp from = B1;
        Timestamp thru = B2;
        PlanRuleData onFrom = rule(1, from, thru, INFINITY, INFINITY, true);
        PlanRuleData onThru = rule(1, B0, from, INFINITY, INFINITY, true);
        PlanRuleData inside = rule(1, B0, thru, INFINITY, INFINITY, true);
        PlanRuleData closedProc = rule(1, B0, thru, B0, B1, true);

        Operation op = (Operation) PlanRuleFinder.ruleId().eq(1)
                .and(PlanRuleFinder.businessDate().eq(from))
                .and(PlanRuleFinder.processingDate().eq(INFINITY));
        QueryPlan plan = new QueryPlanner().plan(new AnalyzedOperation(op), planRule());
        QueryPlanInterpreter interp = new QueryPlanInterpreter();

        Operation analyzed = new AnalyzedOperation(op).getAnalyzedOperation();
        assertThat(interp.accepts(plan, itemOf(onFrom), onFrom)).isTrue();
        assertThat(reladomoSqlPass(analyzed, onFrom)).isTrue();

        assertThat(interp.accepts(plan, itemOf(onThru), onThru)).isFalse();
        assertThat(reladomoSqlPass(analyzed, onThru)).isFalse();

        assertThat(interp.accepts(plan, itemOf(inside), inside)).isTrue();
        assertThat(interp.accepts(plan, itemOf(closedProc), closedProc)).isFalse();
    }

    @Test
    void should_handle_operations_whose_matches_returns_null() {
        Operation residualOp = ResidualPredicateTest.nullMatchesOperation();
        ResidualPredicate residual = new ResidualPredicate(residualOp);
        assertThatThrownBy(() -> residual.matchesOrThrow("candidate"))
                .isInstanceOf(ReladynamoResidualEvaluationException.class);

        QueryPlan plan = QueryPlan.builder()
                .className("io.reladynamo.core.plan.fixture.PlanRule")
                .tableName("PLAN_RULE")
                .kind(PlanKind.QUERY)
                .keyCondition(KeyCondition.partitionEquals("pk", "v1#PlanRule#1"))
                .residual(residual)
                .estimatedItemsExamined(16)
                .estimatedItemsReturned(1)
                .build();
        Map<String, ExpressionValue> item = new LinkedHashMap<String, ExpressionValue>();
        item.put("pk", ExpressionValue.s("v1#PlanRule#1"));
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        assertThatThrownBy(() -> interp.accepts(plan, item, new Object()))
                .isInstanceOf(ReladynamoResidualEvaluationException.class);
    }

    private static void assertOracle(int seed) {
        PlanReladomoBoot.ensure();
        Random rnd = new Random(seed);
        Operation tree = randomTree(rnd, 0, 4);
        PhysicalDesign design = planRule();
        PlannerConfig config = PlannerConfig.builder()
                .pkFanOutLimit(100)
                .allowTableScan(false)
                .build();
        QueryPlan plan;
        try {
            plan = new QueryPlanner().plan(new AnalyzedOperation((Operation) tree), design, config);
        } catch (ReladynamoUnplannableOperationException refused) {
            return;
        }
        QueryPlanInterpreter interp = new QueryPlanInterpreter();
        List<PlanRuleData> items = syntheticItems();
        Operation analyzed = new AnalyzedOperation((Operation) tree).getAnalyzedOperation();
        for (int i = 0; i < items.size(); i++) {
            PlanRuleData data = items.get(i);
            boolean expected = reladomoSqlPass(analyzed, data);
            boolean actual;
            try {
                actual = interp.accepts(plan, itemOf(data), data);
            } catch (ClassCastException e) {
                throw new AssertionError("seed=" + seed + " residual CCE for " + tree, e);
            } catch (ReladynamoResidualEvaluationException e) {
                throw new AssertionError("seed=" + seed + " residual null for " + tree, e);
            }
            assertThat(actual)
                    .as("seed=%s tree=%s item ruleId=%s from=%s expectedReladomo=%s",
                            Integer.valueOf(seed), tree, Integer.valueOf(data.getRuleId()),
                            data.getBusinessDateFrom(), Boolean.valueOf(expected))
                    .isEqualTo(expected);
        }
    }

    private static Operation randomTree(Random rnd, int depth, int maxDepth) {
        return randomTree(rnd, depth, maxDepth, new java.util.HashSet<String>());
    }

    /**
     * @param usedAsOfAxes axes for which an as-of operation has already been emitted in this tree.
     *                     Reladomo throws {@code "can't have multiple asOf operations"} when a tree
     *                     carries two for one axis, so emitting that is a defect in the generator
     *                     rather than a finding about the planner. Without this guard the property
     *                     is flaky: it depends on which seeds jqwik happens to draw.
     */
    private static Operation randomTree(Random rnd, int depth, int maxDepth,
                                        java.util.Set<String> usedAsOfAxes) {
        if (depth >= maxDepth) {
            return randomLeaf(rnd, usedAsOfAxes);
        }
        int kind = rnd.nextInt(6);
        if (kind == 0) {
            return (Operation) randomTree(rnd, depth + 1, maxDepth, usedAsOfAxes)
                    .and(randomTree(rnd, depth + 1, maxDepth, usedAsOfAxes));
        }
        if (kind == 1) {
            return (Operation) randomTree(rnd, depth + 1, maxDepth, usedAsOfAxes)
                    .or(randomTree(rnd, depth + 1, maxDepth, usedAsOfAxes));
        }
        return randomLeaf(rnd, usedAsOfAxes);
    }

    private static Operation randomLeaf(Random rnd, java.util.Set<String> usedAsOfAxes) {
        int pick = rnd.nextInt(12);
        // Re-roll an as-of pick onto a non-temporal leaf when that axis is already constrained.
        if (pick == 2 && !usedAsOfAxes.add("businessDate")) {
            pick = 0;
        } else if ((pick == 3 || pick == 4) && !usedAsOfAxes.add("processingDate")) {
            pick = 5;
        }
        switch (pick) {
            case 0:
                return (Operation) PlanRuleFinder.ruleId().eq(rnd.nextInt(4) + 1);
            case 1:
                IntHashSet set = new IntHashSet();
                int n = 1 + rnd.nextInt(3);
                for (int i = 0; i < n; i++) {
                    set.add(rnd.nextInt(4) + 1);
                }
                return (Operation) PlanRuleFinder.ruleId().in(set);
            case 2:
                return (Operation) PlanRuleFinder.businessDate().eq(pickTs(rnd));
            case 3:
                return (Operation) PlanRuleFinder.processingDate().eq(INFINITY);
            case 4:
                return (Operation) PlanRuleFinder.processingDate().eq(pickTs(rnd));
            case 5:
                return (Operation) PlanRuleFinder.active().eq(rnd.nextBoolean());
            case 6:
                return (Operation) PlanRuleFinder.priority().greaterThan(rnd.nextInt(10));
            case 7:
                return (Operation) PlanRuleFinder.ruleName().startsWith("r");
            case 8:
                return (Operation) PlanRuleFinder.ruleName().endsWith("x");
            case 9:
                return (Operation) PlanRuleFinder.resultLabel().eq("KEEP");
            case 10:
                return new All(PlanRuleFinder.ruleId());
            default:
                return (Operation) PlanRuleFinder.ruleId().notEq(99);
        }
    }

    /**
     * Reladomo SQL semantics on a data object. {@code AsOfEqOperation.matches} casts to the
     * business class and compares the object's as-of date, not from/thru containment — that is
     * the SQL path, which the planner encodes as a FilterExpression. Residual non-as-of
     * predicates still go through {@code matches} on the data object.
     */
    static boolean reladomoSqlPass(Operation op, PlanRuleData data) {
        if (op == null) {
            return true;
        }
        if (ReladomoOperationAccess.isNone(op)) {
            return false;
        }
        if (ReladomoOperationAccess.isAll(op) || ReladomoOperationAccess.isNoOperation(op)) {
            return true;
        }
        if (op instanceof AsOfEqOperation) {
            return asOfContains((AsOfEqOperation) op, data);
        }
        if (op instanceof AndOperation || op instanceof MultiEqualityOperation) {
            List<Operation> kids = ReladomoOperationAccess.operands(op);
            for (int i = 0; i < kids.size(); i++) {
                if (!reladomoSqlPass(kids.get(i), data)) {
                    return false;
                }
            }
            return true;
        }
        if (op instanceof OrOperation) {
            List<Operation> kids = ReladomoOperationAccess.orOperands(op);
            for (int i = 0; i < kids.size(); i++) {
                if (reladomoSqlPass(kids.get(i), data)) {
                    return true;
                }
            }
            return false;
        }
        try {
            return Boolean.TRUE.equals(op.matches(data));
        } catch (ClassCastException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean asOfContains(AsOfEqOperation asOf, PlanRuleData data) {
        AsOfAttribute attr = (AsOfAttribute) asOf.getAttribute();
        Timestamp param = asOf.getParameter();
        Timestamp inf = attr.getInfinityDate();
        Timestamp from;
        Timestamp thru;
        if (attr.isProcessingDate()) {
            from = data.getProcessingDateFrom();
            thru = data.getProcessingDateTo();
        } else {
            from = data.getBusinessDateFrom();
            thru = data.getBusinessDateTo();
        }
        if (param != null && inf != null && param.equals(inf)) {
            return thru != null && thru.equals(inf);
        }
        if (attr.isToIsInclusive()) {
            return from != null && thru != null && from.before(param) && !thru.before(param);
        }
        return from != null && thru != null && !from.after(param) && thru.after(param);
    }

    private static Timestamp pickTs(Random rnd) {
        Timestamp[] ts = {B0, B1, B2, INFINITY};
        return ts[rnd.nextInt(ts.length)];
    }

    private static List<PlanRuleData> syntheticItems() {
        List<PlanRuleData> items = new ArrayList<PlanRuleData>();
        int[] ids = {1, 2, 3, 99};
        for (int i = 0; i < ids.length; i++) {
            items.add(rule(ids[i], B0, B2, B0, INFINITY, true));
            items.add(rule(ids[i], B1, INFINITY, B1, INFINITY, true));
            items.add(rule(ids[i], B1, B1, B0, INFINITY, true));
            items.add(rule(ids[i], B0, B1, B0, B1, false));
            Timestamp plus1 = new Timestamp(B1.getTime() + 1L);
            items.add(rule(ids[i], plus1, INFINITY, B0, INFINITY, true));
        }
        return items;
    }

    private static PlanRuleData rule(int id, Timestamp from, Timestamp thru,
                                     Timestamp in, Timestamp out, boolean active) {
        PlanRuleData d = new PlanRuleData();
        d.setRuleId(id);
        d.setRuleName("r" + id);
        d.setPriority(id);
        d.setResultLabel("KEEP");
        d.setActive(active);
        d.setBusinessDateFrom(from);
        d.setBusinessDateTo(thru);
        d.setProcessingDateFrom(in);
        d.setProcessingDateTo(out);
        return d;
    }

    static Map<String, ExpressionValue> itemOf(PlanRuleData d) {
        Map<String, Object> pk = new LinkedHashMap<String, Object>();
        pk.put("ruleId", Integer.valueOf(d.getRuleId()));
        String encodedPk = PartitionKeyEncoder.partitionKey(planRule(), pk);
        String encodedSk = PartitionKeyEncoder.sortKey(planRule(),
                d.getProcessingDateFrom(), d.getBusinessDateFrom());
        Map<String, ExpressionValue> item = new LinkedHashMap<String, ExpressionValue>();
        item.put("pk", ExpressionValue.s(encodedPk));
        item.put("sk", ExpressionValue.s(encodedSk));
        item.put("RULE_ID", ExpressionValue.n(Integer.toString(d.getRuleId())));
        item.put("RULE_NAME", ExpressionValue.s(d.getRuleName()));
        item.put("PRIORITY", ExpressionValue.n(Integer.toString(d.getPriority())));
        item.put("RESULT_LABEL", ExpressionValue.s(d.getResultLabel()));
        item.put("IS_ACTIVE", ExpressionValue.bool(d.isActive()));
        item.put("FROM_Z", ExpressionValue.s(TemporalEncoder.encode(d.getBusinessDateFrom())));
        item.put("THRU_Z", ExpressionValue.s(TemporalEncoder.encode(d.getBusinessDateTo())));
        item.put("IN_Z", ExpressionValue.s(TemporalEncoder.encode(d.getProcessingDateFrom())));
        item.put("OUT_Z", ExpressionValue.s(TemporalEncoder.encode(d.getProcessingDateTo())));
        return item;
    }
}
