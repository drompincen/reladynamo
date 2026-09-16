package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import com.gs.fw.common.mithra.util.DefaultInfinityTimestamp;
import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.plan.fixture.PlanCustomerFinder;
import io.reladynamo.core.plan.fixture.PlanPositionFinder;
import io.reladynamo.core.plan.fixture.PlanRuleFinder;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

final class PlanFixtures {

    static final Timestamp INFINITY = DefaultInfinityTimestamp.getDefaultInfinity();

    static {
        PlanReladomoBoot.ensure();
    }

    private PlanFixtures() {
    }

    static PhysicalDesign planRule() {
        EntityMapping entity = new EntityMapping(
                "io.reladynamo.core.plan.fixture.PlanRule",
                "PLAN_RULE",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, INFINITY),
                Arrays.asList(
                        new AttributeMapping("ruleId", "RULE_ID", "int", true, false),
                        new AttributeMapping("ruleName", "RULE_NAME", "String", false, false),
                        new AttributeMapping("priority", "PRIORITY", "int", false, false),
                        new AttributeMapping("resultLabel", "RESULT_LABEL", "String", false, false),
                        new AttributeMapping("active", "IS_ACTIVE", "boolean", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)
                ));
        return PhysicalDesign.builder(entity)
                .infinity(INFINITY)
                .businessToInclusive(false)
                .processingToInclusive(false)
                .build();
    }

    static PhysicalDesign planRuleWithCurrentGsi() {
        PhysicalDesign base = planRule();
        return PhysicalDesign.builder(base.entity())
                .infinity(INFINITY)
                .addGsi(GsiSpec.sparseCurrent("gsi_current", Arrays.asList("ruleId")))
                .build();
    }

    static PhysicalDesign planCustomer() {
        EntityMapping entity = new EntityMapping(
                "io.reladynamo.core.plan.fixture.PlanCustomer",
                "PLAN_CUSTOMER",
                TemporalMapping.none(),
                Arrays.asList(
                        new AttributeMapping("id", "ID", "int", true, false),
                        new AttributeMapping("email", "EMAIL", "String", false, false),
                        new AttributeMapping("status", "STATUS", "String", false, false)
                ));
        return PhysicalDesign.builder(entity)
                .addGsi(GsiSpec.uniqueAttribute("gsi_email", "email"))
                .build();
    }

    static PhysicalDesign planPosition() {
        EntityMapping entity = new EntityMapping(
                "io.reladynamo.core.plan.fixture.PlanPosition",
                "PLAN_POSITION",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, INFINITY),
                Arrays.asList(
                        new AttributeMapping("accountId", "ACCOUNT_ID", "long", true, false),
                        new AttributeMapping("productId", "PRODUCT_ID", "int", true, false),
                        new AttributeMapping("quantity", "QUANTITY", "double", false, false),
                        new AttributeMapping("status", "STATUS", "String", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)
                ));
        return PhysicalDesign.builder(entity).infinity(INFINITY).build();
    }

    static PhysicalDesign planPositionWithCurrentGsi() {
        PhysicalDesign base = planPosition();
        return PhysicalDesign.builder(base.entity())
                .infinity(INFINITY)
                .addGsi(GsiSpec.sparseCurrent("gsi_current", Arrays.asList("accountId", "productId")))
                .build();
    }

    static Timestamp utc(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo, d, 0, 0, 0);
        Timestamp t = new Timestamp(c.getTimeInMillis());
        t.setNanos(0);
        return t;
    }

    static QueryPlan plan(Operation op, PhysicalDesign design) {
        return plan(op, design, PlannerConfig.defaults());
    }

    static QueryPlan plan(Operation op, PhysicalDesign design, PlannerConfig config) {
        return plan(op, design, config, 0, PlanningPurpose.FIND);
    }

    static QueryPlan plan(Operation op, PhysicalDesign design, PlannerConfig config,
                          int rowcount, PlanningPurpose purpose) {
        return plan(op, null, design, config, rowcount, purpose);
    }

    static QueryPlan plan(Operation op, OrderBy orderBy, PhysicalDesign design) {
        return plan(op, orderBy, design, PlannerConfig.defaults(), 0, PlanningPurpose.FIND);
    }

    static QueryPlan plan(Operation op, OrderBy orderBy, PhysicalDesign design,
                          PlannerConfig config, int rowcount) {
        return plan(op, orderBy, design, config, rowcount, PlanningPurpose.FIND);
    }

    static QueryPlan plan(Operation op, OrderBy orderBy, PhysicalDesign design,
                          PlannerConfig config, int rowcount, PlanningPurpose purpose) {
        PlanReladomoBoot.ensure();
        AnalyzedOperation analyzed = new AnalyzedOperation(op);
        return new QueryPlanner().plan(new PlanningRequest(
                analyzed, orderBy, design, config, rowcount, 1, purpose));
    }

    static Operation ruleCurrent(int ruleId, Timestamp businessDate) {
        return (Operation) PlanRuleFinder.ruleId().eq(ruleId)
                .and(PlanRuleFinder.businessDate().eq(businessDate));
    }

    static Operation customerById(int id) {
        return (Operation) PlanCustomerFinder.id().eq(id);
    }

    static Operation positionCurrent(long accountId, int productId, Timestamp businessDate) {
        return (Operation) PlanPositionFinder.accountId().eq(accountId)
                .and(PlanPositionFinder.productId().eq(productId))
                .and(PlanPositionFinder.businessDate().eq(businessDate));
    }
}
