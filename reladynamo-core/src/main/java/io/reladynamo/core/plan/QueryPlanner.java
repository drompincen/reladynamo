package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.AtomicEqualityOperation;
import com.gs.fw.common.mithra.finder.AtomicNotEqualityOperation;
import com.gs.fw.common.mithra.finder.AtomicSetBasedOperation;
import com.gs.fw.common.mithra.finder.MappedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import com.gs.fw.common.mithra.finder.RangeOperation;
import com.gs.fw.common.mithra.finder.asofop.AsOfEqOperation;
import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import com.gs.fw.common.mithra.finder.string.StringLikeOperation;
import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.plan.reladomo.ReladomoOperationAccess;
import io.reladynamo.core.temporal.TemporalEncoder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure function {@code AnalyzedOperation → QueryPlan}. No I/O, no AWS types, no {@code DynamoDbClient}.
 *
 * <p>Always plans {@link AnalyzedOperation#getAnalyzedOperation()}, never
 * {@link AnalyzedOperation#getOriginalOperation()} — the analyzed form carries the as-of
 * predicates Reladomo injects.
 */
public final class QueryPlanner {

    public QueryPlan plan(PlanningRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.purpose() == PlanningPurpose.COMPUTE_FUNCTION) {
            throw plan010(request.design().className());
        }
        // Analyzed form only. Planning the original silently drops injected as-of predicates.
        Operation op = request.analyzedOperation().getAnalyzedOperation();
        return planOperation(op, request);
    }

    public QueryPlan plan(AnalyzedOperation analyzed, PhysicalDesign design, PlannerConfig config) {
        return plan(new PlanningRequest(analyzed, null, design, config, 0, 1, PlanningPurpose.FIND));
    }

    public QueryPlan plan(AnalyzedOperation analyzed, PhysicalDesign design) {
        return plan(analyzed, design, PlannerConfig.defaults());
    }

    private QueryPlan planOperation(Operation op, PlanningRequest request) {
        PhysicalDesign design = request.design();
        PlannerConfig config = request.config();

        if (ReladomoOperationAccess.isNone(op)) {
            return emptyPlan(request);
        }
        if (ReladomoOperationAccess.isTupleExists(op)) {
            throw plan011(design.className());
        }
        if (ReladomoOperationAccess.isAsOfInfiniteNull(op)) {
            throw new ReladynamoUnplannableOperationException(
                    "RELADYNAMO-PLAN-005: No DynamoDB access path for " + design.className()
                            + ": AsOfEqInfiniteNullOperation is rejected (keys cannot be null). Operation: "
                            + ReladomoOperationAccess.dump(op));
        }

        if (request.purpose() == PlanningPurpose.CACHE_LOAD) {
            if (!config.allowTableScan() && !config.allowFullCacheLoad()) {
                throw ReladynamoScanRequiredException.plan001(
                        design.className(), design.tableName(), op);
            }
            return scanPlan(request, Collections.singletonList(op), op);
        }

        Node tree = Node.decompose(op);
        tree = Node.normalize(tree);
        if (tree.type == Node.Type.NONE) {
            return emptyPlan(request);
        }
        if (tree.containsTupleExists()) {
            throw plan011(design.className());
        }

        List<List<Operation>> dnf;
        try {
            dnf = Node.toDnf(tree, config.pkFanOutLimit());
        } catch (DnfExplosion e) {
            if (tree.type == Node.Type.AND && hasCommonCompletePk(tree, design)) {
                dnf = Collections.singletonList(Node.atoms(tree));
            } else {
                throw plan002(e.clauseCount, config.pkFanOutLimit(), design.className(), op);
            }
        }

        if (dnf.size() > config.pkFanOutLimit()) {
            throw plan002(dnf.size(), config.pkFanOutLimit(), design.className(), op);
        }

        List<QueryPlan> chosen = new ArrayList<QueryPlan>(dnf.size());
        for (int i = 0; i < dnf.size(); i++) {
            QueryPlan part = planConjunction(dnf.get(i), request, op);
            if (part.kind() != PlanKind.EMPTY) {
                chosen.add(part);
            }
        }

        QueryPlan result;
        if (chosen.isEmpty()) {
            result = emptyPlan(request);
        } else if (chosen.size() == 1) {
            result = chosen.get(0);
        } else {
            result = fanOutPlan(chosen, request, op);
        }
        return attachOrderBy(result, request);
    }

    private QueryPlan planConjunction(List<Operation> atoms, PlanningRequest request, Operation whole) {
        PhysicalDesign design = request.design();
        PlannerConfig config = request.config();
        Conjunction c = Conjunction.classify(atoms, design);

        if (c.rejected != null) {
            throw c.rejected;
        }
        if (c.isNone) {
            return emptyPlan(request);
        }
        if (c.isAll && c.pkEq.isEmpty() && c.pkIn.isEmpty()) {
            return scanOrThrow(request, atoms, whole);
        }

        List<Map<String, Object>> pkCombos = expandPk(c, design, config, whole);
        if (pkCombos == null) {
            QueryPlan gsi = tryGsi(c, request, whole);
            if (gsi != null) {
                return gsi;
            }
            return scanOrThrow(request, atoms, whole);
        }
        if (pkCombos.size() > config.pkFanOutLimit()) {
            throw plan002(pkCombos.size(), config.pkFanOutLimit(), design.className(), whole);
        }
        if (pkCombos.size() > 1) {
            List<QueryPlan> parts = new ArrayList<QueryPlan>(pkCombos.size());
            for (int i = 0; i < pkCombos.size(); i++) {
                parts.add(baseTablePlan(pkCombos.get(i), c, request, whole));
            }
            return fanOutPlan(parts, request, whole);
        }
        return baseTablePlan(pkCombos.get(0), c, request, whole);
    }

    private List<Map<String, Object>> expandPk(Conjunction c, PhysicalDesign design,
                                               PlannerConfig config, Operation whole) {
        List<AttributeMapping> pks = design.primaryKeyAttributes();
        Map<String, List<Object>> domains = new LinkedHashMap<String, List<Object>>();
        for (int i = 0; i < pks.size(); i++) {
            String name = pks.get(i).javaName();
            if (c.pkEq.containsKey(name)) {
                domains.put(name, Collections.singletonList(c.pkEq.get(name)));
            } else if (c.pkIn.containsKey(name)) {
                domains.put(name, c.pkIn.get(name));
            } else {
                return null;
            }
        }
        refuseIfCartesianExceeds(domains, config, design, whole);
        List<Map<String, Object>> combos = cartesianMaps(domains);
        if (combos.size() > config.pkFanOutLimit()) {
            throw plan002(combos.size(), config.pkFanOutLimit(), design.className(), whole);
        }
        return combos;
    }

    private QueryPlan baseTablePlan(Map<String, Object> pkValues, Conjunction c,
                                    PlanningRequest request, Operation whole) {
        PhysicalDesign design = request.design();
        PlannerConfig config = request.config();
        String encodedPk = PartitionKeyEncoder.partitionKey(design, pkValues);
        TemporalMapping.Flavour flavour = design.temporal().flavour();
        boolean dated = flavour != TemporalMapping.Flavour.NONE;

        Timestamp processingFromEq = asTimestamp(c.fromEq.get(design.processingFromJavaName()));
        Timestamp businessFromEq = asTimestamp(c.fromEq.get(design.businessFromJavaName()));

        boolean currentAsof = isCurrentAsof(c, design);
        boolean pointGet = !dated;
        boolean exactRectangle = dated && hasAllFromEqualities(design, processingFromEq, businessFromEq);

        Expr expr = new Expr();
        KeyCondition key;
        PlanKind kind;
        String fastPath;
        String index = PhysicalDesign.PRIMARY_INDEX;
        boolean consistent = true;

        GsiSpec currentGsi = currentGsi(design, request);
        if (currentAsof && currentGsi != null && !request.config().inTransaction()
                && request.config().allowGsi() && !request.purpose().forbidsGsi()
                && config.estimatedVersionsPerKey() > 4
                && c.businessAsOf != null) {
            Timestamp b = c.businessAsOf;
            String gsiPk = encodedPk;
            String skLo = "v1#B#";
            String skHi = PartitionKeyEncoder.currentBusinessSk(b);
            key = KeyCondition.partitionAndBetween(
                    currentGsi.partitionKeyAttributeName(), currentGsi.sortKeyAttributeName(),
                    gsiPk, skLo, skHi);
            kind = PlanKind.QUERY;
            fastPath = QueryPlan.FAST_PATH_CURRENT_ASOF;
            index = currentGsi.name();
            consistent = false;
            appendThruFilter(expr, design, b, true);
        } else if (pointGet) {
            String sk = PhysicalDesign.NON_DATED_SK;
            key = KeyCondition.partitionAndSortEquals(design.pkAttributeName(), design.skAttributeName(),
                    encodedPk, sk);
            kind = PlanKind.GET_ITEM;
            fastPath = QueryPlan.FAST_PATH_POINT_GET;
        } else if (exactRectangle) {
            String sk = PartitionKeyEncoder.sortKey(design, processingFromEq, businessFromEq);
            key = KeyCondition.partitionAndSortEquals(design.pkAttributeName(), design.skAttributeName(),
                    encodedPk, sk);
            kind = PlanKind.GET_ITEM;
            fastPath = QueryPlan.FAST_PATH_POINT_GET;
            appendAsOfFilters(expr, c, design);
        } else if (processingFromEq != null && businessFromEq != null) {
            String sk = PartitionKeyEncoder.sortKey(design, processingFromEq, businessFromEq);
            key = KeyCondition.partitionAndSortEquals(design.pkAttributeName(), design.skAttributeName(),
                    encodedPk, sk);
            kind = PlanKind.GET_ITEM;
            fastPath = QueryPlan.FAST_PATH_NONE;
            appendAsOfFilters(expr, c, design);
        } else if (processingFromEq != null && c.businessFromLo != null && c.businessFromHi != null) {
            String lo = PartitionKeyEncoder.sortKey(design, processingFromEq, c.businessFromLo);
            String hi = PartitionKeyEncoder.sortKey(design, processingFromEq, c.businessFromHi);
            key = KeyCondition.partitionAndBetween(design.pkAttributeName(), design.skAttributeName(),
                    encodedPk, lo, hi);
            kind = PlanKind.QUERY;
            fastPath = QueryPlan.FAST_PATH_NONE;
            appendAsOfFilters(expr, c, design);
        } else if (processingFromEq != null) {
            String prefix = PartitionKeyEncoder.processingPrefix(processingFromEq);
            key = KeyCondition.partitionAndBeginsWith(design.pkAttributeName(), design.skAttributeName(),
                    encodedPk, prefix);
            kind = PlanKind.QUERY;
            fastPath = QueryPlan.FAST_PATH_NONE;
            appendAsOfFilters(expr, c, design);
        } else {
            key = KeyCondition.partitionEquals(design.pkAttributeName(), encodedPk);
            kind = PlanKind.QUERY;
            fastPath = currentAsof ? QueryPlan.FAST_PATH_CURRENT_ASOF : QueryPlan.FAST_PATH_NONE;
            appendAsOfFilters(expr, c, design);
        }

        ResidualPredicate residual = appendPayload(expr, c.payload, design, config);
        if (!c.keptOr.isEmpty()) {
            residual = andResidual(residual, appendOrFilter(expr, c.keptOr, design, config));
        }

        FilterExpression filter = expr.toFilter();
        Integer limit = dynamoLimit(request.rowcount(), filter, residual);
        int examined;
        int returned;
        if (kind == PlanKind.GET_ITEM) {
            examined = 1;
            returned = 1;
        } else if (currentAsof && currentGsi != null && currentGsi.name().equals(index)) {
            examined = 1;
            returned = 1;
        } else if (currentAsof) {
            examined = config.estimatedVersionsPerKey();
            returned = 1;
        } else {
            examined = config.estimatedVersionsPerKey();
            returned = Math.max(1, examined / 4);
        }

        QueryPlan.Builder b = QueryPlan.builder()
                .className(design.className())
                .tableName(design.tableName())
                .indexName(index)
                .kind(kind)
                .keyCondition(key)
                .filterExpression(filter)
                .residual(residual)
                .consistentRead(consistent)
                .scanIndexForward(true)
                .dynamoLimit(limit)
                .estimatedItemsExamined(examined)
                .estimatedItemsReturned(returned)
                .fastPath(fastPath)
                .rowcount(request.rowcount())
                .purpose(request.purpose());
        applyConfigLimits(b, config);
        return b.build();
    }

    private boolean isCurrentAsof(Conjunction c, PhysicalDesign design) {
        TemporalMapping.Flavour flavour = design.temporal().flavour();
        if (flavour == TemporalMapping.Flavour.NONE) {
            return false;
        }
        if (c.hasMapped) {
            return false;
        }
        if (flavour == TemporalMapping.Flavour.BITEMPORAL || flavour == TemporalMapping.Flavour.BUSINESS_ONLY) {
            if (c.businessAsOf == null) {
                return false;
            }
        }
        if (flavour == TemporalMapping.Flavour.BITEMPORAL || flavour == TemporalMapping.Flavour.AUDIT_ONLY) {
            if (c.processingAsOf == null) {
                return false;
            }
            Timestamp inf = axisInfinity(c.processingAsOfAttr, design);
            if (inf == null || !c.processingAsOf.equals(inf)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reladomo compares as-of infinity against {@code AsOfAttribute.getInfinityDate()}, not a
     * conventional UTC timestamp. {@link PhysicalDesign#infinity()} can disagree (the XML parser
     * does not classload {@code infinityDate} snippets), and {@code thru > infinity} is empty.
     */
    private static Timestamp axisInfinity(AsOfAttribute attr, PhysicalDesign design) {
        if (attr != null) {
            return attr.getInfinityDate();
        }
        return design.infinity();
    }

    private static boolean axisToInclusive(AsOfAttribute attr, PhysicalDesign design, boolean business) {
        if (attr != null) {
            return attr.isToIsInclusive();
        }
        return business ? design.businessToInclusive() : design.processingToInclusive();
    }

    private boolean hasAllFromEqualities(PhysicalDesign design, Timestamp processingFrom, Timestamp businessFrom) {
        TemporalMapping.Flavour flavour = design.temporal().flavour();
        if (flavour == TemporalMapping.Flavour.BITEMPORAL) {
            return processingFrom != null && businessFrom != null;
        }
        if (flavour == TemporalMapping.Flavour.AUDIT_ONLY) {
            return processingFrom != null;
        }
        if (flavour == TemporalMapping.Flavour.BUSINESS_ONLY) {
            return businessFrom != null;
        }
        return false;
    }

    private GsiSpec currentGsi(PhysicalDesign design, PlanningRequest request) {
        if (!request.config().allowGsi() || request.config().inTransaction() || request.purpose().forbidsGsi()) {
            return null;
        }
        List<GsiSpec> gsis = design.gsis();
        for (int i = 0; i < gsis.size(); i++) {
            GsiSpec g = gsis.get(i);
            if (g.sparseCurrent() && GsiSpec.SK_CURRENT_BUSINESS.equals(g.sortKeyKind())) {
                return g;
            }
        }
        return null;
    }

    private QueryPlan tryGsi(Conjunction c, PlanningRequest request, Operation whole) {
        PhysicalDesign design = request.design();
        if (!request.config().allowGsi() || request.config().inTransaction() || request.purpose().forbidsGsi()) {
            return null;
        }
        List<GsiSpec> gsis = design.gsis();
        for (int i = 0; i < gsis.size(); i++) {
            GsiSpec gsi = gsis.get(i);
            if (gsi.sparseCurrent()) {
                continue;
            }
            List<Map<String, Object>> combos = expandGsiPk(c, gsi, request.config(), whole, design);
            if (combos == null || combos.isEmpty()) {
                continue;
            }
            List<QueryPlan> parts = new ArrayList<QueryPlan>(combos.size());
            for (int j = 0; j < combos.size(); j++) {
                parts.add(gsiQueryPlan(gsi, combos.get(j), c, request));
            }
            if (parts.size() == 1) {
                return parts.get(0);
            }
            return fanOutPlan(parts, request, whole);
        }
        return null;
    }

    /**
     * Same cartesian expansion as {@link #expandPk}, but over a GSI partition key: equality or
     * {@code IN} on those attributes. {@code IN} becomes {@link PlanKind#QUERY_FAN_OUT} — one
     * Query per value — not a DynamoDB {@code IN} key condition (unsupported) and never a Scan.
     */
    private List<Map<String, Object>> expandGsiPk(Conjunction c, GsiSpec gsi, PlannerConfig config,
                                                  Operation whole, PhysicalDesign design) {
        List<String> gsiPk = gsi.partitionKeyJavaNames();
        Map<String, List<Object>> domains = new LinkedHashMap<String, List<Object>>();
        for (int i = 0; i < gsiPk.size(); i++) {
            String n = gsiPk.get(i);
            if (c.pkEq.containsKey(n)) {
                domains.put(n, Collections.singletonList(c.pkEq.get(n)));
            } else if (c.payloadEq.containsKey(n)) {
                domains.put(n, Collections.singletonList(c.payloadEq.get(n)));
            } else if (c.pkIn.containsKey(n)) {
                domains.put(n, c.pkIn.get(n));
            } else if (c.payloadIn.containsKey(n)) {
                domains.put(n, c.payloadIn.get(n));
            } else {
                return null;
            }
        }
        refuseIfCartesianExceeds(domains, config, design, whole);
        List<Map<String, Object>> combos = cartesianMaps(domains);
        if (combos.size() > config.pkFanOutLimit()) {
            throw plan002(combos.size(), config.pkFanOutLimit(), design.className(), whole);
        }
        return combos;
    }

    private QueryPlan gsiQueryPlan(GsiSpec gsi, Map<String, Object> values, Conjunction c,
                                   PlanningRequest request) {
        PhysicalDesign design = request.design();
        PlannerConfig config = request.config();
        String encoded = encodeGsiPk(design, gsi, values);
        KeyCondition key = KeyCondition.partitionEquals(gsi.partitionKeyAttributeName(), encoded);
        Expr expr = new Expr();
        appendAsOfFilters(expr, c, design);
        ResidualPredicate residual = appendPayload(expr, payloadMinusGsiPk(c.payload, gsi),
                design, config);
        FilterExpression filter = expr.toFilter();
        boolean currentAsof = isCurrentAsof(c, design);
        int examined = currentAsof ? config.estimatedVersionsPerKey() : 1;
        int returned = currentAsof ? 1 : 1;
        QueryPlan.Builder b = QueryPlan.builder()
                .className(design.className())
                .tableName(design.tableName())
                .indexName(gsi.name())
                .kind(PlanKind.QUERY)
                .keyCondition(key)
                .filterExpression(filter)
                .residual(residual)
                .consistentRead(false)
                .dynamoLimit(dynamoLimit(request.rowcount(), filter, residual))
                .estimatedItemsExamined(examined)
                .estimatedItemsReturned(returned)
                .fastPath(currentAsof ? QueryPlan.FAST_PATH_CURRENT_ASOF : QueryPlan.FAST_PATH_NONE)
                .rowcount(request.rowcount())
                .purpose(request.purpose());
        applyConfigLimits(b, config);
        return b.build();
    }

    private static List<Operation> payloadMinusGsiPk(List<Operation> payload, GsiSpec gsi) {
        List<String> pkNames = gsi.partitionKeyJavaNames();
        List<Operation> remaining = new ArrayList<Operation>();
        for (int i = 0; i < payload.size(); i++) {
            Operation p = payload.get(i);
            String n = ReladomoOperationAccess.attributeJavaName(p);
            if (n != null && pkNames.contains(n)
                    && (ReladomoOperationAccess.isEquality(p) || ReladomoOperationAccess.isIn(p))) {
                continue;
            }
            remaining.add(p);
        }
        return remaining;
    }

    private String encodeGsiPk(PhysicalDesign design, GsiSpec gsi, Map<String, Object> values) {
        if (gsi.sparseCurrent() || isEntityPrimaryKey(design, gsi.partitionKeyJavaNames())) {
            return PartitionKeyEncoder.partitionKey(design, values);
        }
        return PartitionKeyEncoder.gsiPartitionKey(gsi.partitionKeyJavaNames(), values);
    }

    private static boolean isEntityPrimaryKey(PhysicalDesign design, List<String> names) {
        List<AttributeMapping> pks = design.primaryKeyAttributes();
        if (pks.size() != names.size()) {
            return false;
        }
        for (int i = 0; i < pks.size(); i++) {
            if (!pks.get(i).javaName().equals(names.get(i))) {
                return false;
            }
        }
        return true;
    }

    private QueryPlan scanOrThrow(PlanningRequest request, List<Operation> atoms, Operation whole) {
        PlannerConfig config = request.config();
        PhysicalDesign design = request.design();
        if (request.purpose() == PlanningPurpose.DELETE) {
            if (!config.allowTableScan() || !config.allowScanDelete()) {
                throw new ReladynamoUnplannableOperationException(
                        "RELADYNAMO-PLAN-008: deleteUsingOperation for " + design.className()
                                + " requires a Scan but allowScanDelete=false (allowTableScan is not sufficient for deletes).");
            }
        }
        if (!config.allowTableScan()) {
            throw ReladynamoScanRequiredException.plan001(design.className(), design.tableName(), whole);
        }
        return scanPlan(request, atoms, whole);
    }

    private QueryPlan scanPlan(PlanningRequest request, List<Operation> atoms, Operation whole) {
        PhysicalDesign design = request.design();
        Conjunction c = Conjunction.classify(atoms, design);
        Expr expr = new Expr();
        appendAsOfFilters(expr, c, design);
        ResidualPredicate residual = appendPayload(expr, c.payload, design, request.config());
        FilterExpression filter = expr.toFilter();
        QueryPlan.Builder b = QueryPlan.builder()
                .className(design.className())
                .tableName(design.tableName())
                .indexName(PhysicalDesign.PRIMARY_INDEX)
                .kind(PlanKind.SCAN)
                .filterExpression(filter)
                .residual(residual)
                .consistentRead(true)
                .dynamoLimit(null)
                .estimatedItemsExamined(Integer.MAX_VALUE)
                .estimatedItemsReturned(Integer.MAX_VALUE)
                .segmentCount(request.config().parallelScanSegments())
                .fastPath(QueryPlan.FAST_PATH_NONE)
                .rowcount(request.rowcount())
                .purpose(request.purpose());
        applyConfigLimits(b, request.config());
        return b.build();
    }

    private QueryPlan fanOutPlan(List<QueryPlan> parts, PlanningRequest request, Operation whole) {
        int examined = 0;
        int returned = 0;
        String index = PhysicalDesign.PRIMARY_INDEX;
        boolean consistent = true;
        if (!parts.isEmpty()) {
            index = parts.get(0).indexName();
            consistent = parts.get(0).consistentRead();
            for (int i = 1; i < parts.size(); i++) {
                if (!index.equals(parts.get(i).indexName())) {
                    index = PhysicalDesign.PRIMARY_INDEX;
                    consistent = true;
                    break;
                }
            }
        }
        for (int i = 0; i < parts.size(); i++) {
            examined += parts.get(i).estimatedItemsExamined();
            returned += parts.get(i).estimatedItemsReturned();
        }
        QueryPlan.Builder b = QueryPlan.builder()
                .className(request.design().className())
                .tableName(request.design().tableName())
                .indexName(index)
                .kind(PlanKind.QUERY_FAN_OUT)
                .fanOut(parts)
                .consistentRead(consistent)
                .estimatedItemsExamined(examined)
                .estimatedItemsReturned(returned)
                .fastPath(QueryPlan.FAST_PATH_NONE)
                .rowcount(request.rowcount())
                .purpose(request.purpose());
        applyConfigLimits(b, request.config());
        return b.build();
    }

    private QueryPlan emptyPlan(PlanningRequest request) {
        QueryPlan.Builder b = QueryPlan.builder()
                .className(request.design().className())
                .tableName(request.design().tableName())
                .kind(PlanKind.EMPTY)
                .estimatedItemsExamined(0)
                .estimatedItemsReturned(0)
                .fastPath(QueryPlan.FAST_PATH_NONE)
                .rowcount(request.rowcount())
                .purpose(request.purpose());
        applyConfigLimits(b, request.config());
        return b.build();
    }

    private QueryPlan attachOrderBy(QueryPlan plan, PlanningRequest request) {
        OrderBy orderBy = request.orderBy();
        if (orderBy == null) {
            return plan;
        }
        List<SortTerm> terms = OrderByTranslator.terms(orderBy);
        RowOrderComparator comparator = new RowOrderComparator(terms);
        boolean nativeSk = plan.kind() != PlanKind.QUERY_FAN_OUT
                && plan.kind() != PlanKind.SCAN
                && PhysicalDesign.PRIMARY_INDEX.equals(plan.indexName())
                && OrderByTranslator.matchesNativeSk(terms, request.design());
        OrderMode mode = nativeSk ? OrderMode.NATIVE_SK : OrderMode.IN_MEMORY;
        boolean forward = !nativeSk || OrderByTranslator.allAscending(terms);
        if (mode == OrderMode.IN_MEMORY && request.config().inMemoryRowCeiling() == 0) {
            throw new ReladynamoUnplannableOperationException(
                    "RELADYNAMO-PLAN-009: OrderBy " + orderBy
                            + " is not native sort-key order and in-memory sort is disabled (inMemoryRowCeiling=0) for "
                            + request.design().className() + ".");
        }
        QueryPlan.Builder b = plan.toBuilder()
                .orderMode(mode)
                .rowComparator(comparator)
                .scanIndexForward(forward);
        applyConfigLimits(b, request.config());
        if (mode == OrderMode.IN_MEMORY) {
            b.dynamoLimit(null);
            b.fanOut(clearDynamoLimits(plan.fanOut()));
        }
        return b.build();
    }

    private static List<QueryPlan> clearDynamoLimits(List<QueryPlan> children) {
        if (children == null || children.isEmpty()) {
            return children;
        }
        List<QueryPlan> out = new ArrayList<QueryPlan>(children.size());
        for (int i = 0; i < children.size(); i++) {
            QueryPlan child = children.get(i);
            out.add(child.toBuilder()
                    .dynamoLimit(null)
                    .fanOut(clearDynamoLimits(child.fanOut()))
                    .build());
        }
        return out;
    }

    private static Integer dynamoLimit(int rowcount, FilterExpression filter, ResidualPredicate residual) {
        boolean hasFilter = filter != null && !filter.isEmpty();
        boolean hasResidual = residual != null && !residual.isEmpty();
        if (hasFilter || hasResidual) {
            return null;
        }
        if (rowcount <= 0) {
            return null;
        }
        return Integer.valueOf(rowcount);
    }

    private static void appendAsOfFilters(Expr expr, Conjunction c, PhysicalDesign design) {
        TemporalMapping.Flavour flavour = design.temporal().flavour();
        if (flavour == TemporalMapping.Flavour.BITEMPORAL || flavour == TemporalMapping.Flavour.AUDIT_ONLY) {
            if (c.processingAsOf != null) {
                appendAsOfAxis(expr, c.processingAsOf, axisInfinity(c.processingAsOfAttr, design),
                        design.processingFromItemName(), design.processingThruItemName(),
                        axisToInclusive(c.processingAsOfAttr, design, false));
            }
        }
        if (flavour == TemporalMapping.Flavour.BITEMPORAL || flavour == TemporalMapping.Flavour.BUSINESS_ONLY) {
            if (c.businessAsOf != null) {
                appendAsOfAxis(expr, c.businessAsOf, axisInfinity(c.businessAsOfAttr, design),
                        design.businessFromItemName(), design.businessThruItemName(),
                        axisToInclusive(c.businessAsOfAttr, design, true));
            }
        }
        if (c.processingEdge != null && design.processingFromItemName() != null) {
            expr.addToken("#" + expr.name(design.processingFromItemName()) + " = "
                    + expr.value(ExpressionValue.s(TemporalEncoder.encode(c.processingEdge))));
        }
        if (c.businessEdge != null && design.businessFromItemName() != null) {
            expr.addToken("#" + expr.name(design.businessFromItemName()) + " = "
                    + expr.value(ExpressionValue.s(TemporalEncoder.encode(c.businessEdge))));
        }
    }

    private static void appendThruFilter(Expr expr, PhysicalDesign design, Timestamp asOf, boolean business) {
        String thru = business ? design.businessThruItemName() : design.processingThruItemName();
        boolean inclusive = business ? design.businessToInclusive() : design.processingToInclusive();
        String n = expr.name(thru);
        String v = expr.value(ExpressionValue.s(TemporalEncoder.encode(asOf)));
        if (inclusive) {
            expr.addToken("#" + n + " >= " + v);
        } else {
            expr.addToken("#" + n + " > " + v);
        }
    }

    private static void appendAsOfAxis(Expr expr, Timestamp asOf, Timestamp inf,
                                       String fromItem, String thruItem, boolean toInclusive) {
        if (fromItem == null || thruItem == null || asOf == null) {
            return;
        }
        String v = expr.value(ExpressionValue.s(TemporalEncoder.encode(asOf)));
        if (inf != null && asOf.equals(inf)) {
            // Reladomo binds the as-of parameter itself (which equals the axis infinity).
            String thru = expr.name(thruItem);
            expr.addToken("#" + thru + " = " + v);
            return;
        }
        String from = expr.name(fromItem);
        String thru = expr.name(thruItem);
        if (toInclusive) {
            expr.addToken("#" + from + " < " + v);
            expr.addToken("#" + thru + " >= " + v);
        } else {
            expr.addToken("#" + from + " <= " + v);
            expr.addToken("#" + thru + " > " + v);
        }
    }

    private ResidualPredicate appendPayload(Expr expr, List<Operation> payload,
                                            PhysicalDesign design, PlannerConfig config) {
        List<Operation> residualOps = new ArrayList<Operation>();
        for (int i = 0; i < payload.size(); i++) {
            Operation p = payload.get(i);
            if (ReladomoOperationAccess.isMapped(p) || ReladomoOperationAccess.isNotExists(p)
                    || ReladomoOperationAccess.isEndsWith(p) || ReladomoOperationAccess.isNotEndsWith(p)) {
                residualOps.add(p);
                continue;
            }
            if (ReladomoOperationAccess.isLike(p) && p instanceof StringLikeOperation) {
                String pattern = ReladomoOperationAccess.likeParameter((StringLikeOperation) p);
                if (pattern != null && (pattern.indexOf('_') >= 0 || internalPercent(pattern))) {
                    residualOps.add(p);
                    continue;
                }
            }
            if (ReladomoOperationAccess.isIn(p) && p instanceof AtomicSetBasedOperation) {
                AtomicSetBasedOperation in = (AtomicSetBasedOperation) p;
                if (in.getSetSize() > PlannerConfig.DYNAMO_FILTER_IN_CAP) {
                    residualOps.add(p);
                    continue;
                }
            }
            String fragment = payloadFragment(expr, p, design);
            if (fragment == null) {
                residualOps.add(p);
            } else {
                expr.addToken(fragment);
            }
        }
        return residualOf(residualOps);
    }

    private ResidualPredicate appendOrFilter(Expr expr, List<Operation> orOps,
                                             PhysicalDesign design, PlannerConfig config) {
        List<String> parts = new ArrayList<String>();
        List<Operation> residualOps = new ArrayList<Operation>();
        for (int i = 0; i < orOps.size(); i++) {
            String fragment = payloadFragment(expr, orOps.get(i), design);
            if (fragment == null) {
                residualOps.add(orOps.get(i));
            } else {
                parts.add(fragment);
            }
        }
        if (!parts.isEmpty()) {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append(" OR ");
                }
                sb.append(parts.get(i));
            }
            sb.append(")");
            expr.addToken(sb.toString());
        }
        return residualOf(residualOps);
    }

    private static boolean internalPercent(String pattern) {
        if (pattern.length() < 3) {
            return false;
        }
        int first = pattern.indexOf('%');
        int last = pattern.lastIndexOf('%');
        if (first > 0 && last < pattern.length() - 1) {
            return true;
        }
        return first != last && first > 0 && last == pattern.length() - 1;
    }

    private String payloadFragment(Expr expr, Operation p, PhysicalDesign design) {
        Attribute attr = ReladomoOperationAccess.attributeOf(p);
        String javaName = attr == null ? null : attr.getAttributeName();
        if (javaName == null) {
            return null;
        }
        if (design.isAsOfJavaName(javaName)) {
            return null;
        }
        if (!ReladomoOperationAccess.isIsNull(p) && !ReladomoOperationAccess.isIsNotNull(p)
                && isIeeeOrDecimalPayload(p, design)) {
            // R-04: codec stores float/double as IEEE-754 B and BigDecimal as S.
            // DynamoDB N (and raw B / decimal-string order) is not a correct filter.
            return null;
        }
        String item = design.itemNameForJava(javaName);
        String n = expr.name(item);

        if (ReladomoOperationAccess.isIsNull(p)) {
            // Codec stores Java null as an explicit DynamoDB NULL, which exists.
            // Reladomo isNull() also matches a genuinely missing attribute (schema evolution).
            // attribute_type is used instead of `#n = :null` so the check does not depend
            // on DynamoDB comparison-with-NULL type-coercion.
            String nullType = expr.value(ExpressionValue.s("NULL"));
            return "(attribute_not_exists(#" + n + ") OR attribute_type(#" + n + ", " + nullType + "))";
        }
        if (ReladomoOperationAccess.isIsNotNull(p)) {
            String nullType = expr.value(ExpressionValue.s("NULL"));
            return "(attribute_exists(#" + n + ") AND NOT attribute_type(#" + n + ", " + nullType + "))";
        }
        if (ReladomoOperationAccess.isEquality(p) && p instanceof AtomicEqualityOperation) {
            Object v = ((AtomicEqualityOperation) p).getParameterAsObject();
            return "#" + n + " = " + expr.value(toValue(v));
        }
        if (ReladomoOperationAccess.isNotEquality(p)) {
            Object v = notEqParameter(p);
            if (v != null) {
                // Finding 27: DynamoDB `<>` is true for a stored NULL (type mismatch).
                // SQL three-valued logic: NULL <> v is UNKNOWN, not TRUE. Reladomo
                // isNull() matches both an explicit NULL and a genuinely missing
                // attribute (schema evolution), so notEq must exclude both. Reuse
                // the R-05 isNotNull shape rather than a second idiom. Residual
                // IEEE/decimal notEq already returns false when actual == null.
                String nullType = expr.value(ExpressionValue.s("NULL"));
                return "(attribute_exists(#" + n + ") AND NOT attribute_type(#" + n + ", "
                        + nullType + ") AND #" + n + " <> " + expr.value(toValue(v)) + ")";
            }
        }
        if (ReladomoOperationAccess.isGreaterThanEquals(p) && p instanceof RangeOperation) {
            return "#" + n + " >= " + expr.value(toValue(ReladomoOperationAccess.rangeParameter((RangeOperation) p)));
        }
        if (ReladomoOperationAccess.isGreaterThan(p) && p instanceof RangeOperation) {
            return "#" + n + " > " + expr.value(toValue(ReladomoOperationAccess.rangeParameter((RangeOperation) p)));
        }
        if (ReladomoOperationAccess.isLessThanEquals(p) && p instanceof RangeOperation) {
            return "#" + n + " <= " + expr.value(toValue(ReladomoOperationAccess.rangeParameter((RangeOperation) p)));
        }
        if (ReladomoOperationAccess.isLessThan(p) && p instanceof RangeOperation) {
            return "#" + n + " < " + expr.value(toValue(ReladomoOperationAccess.rangeParameter((RangeOperation) p)));
        }
        if (ReladomoOperationAccess.isIn(p) && p instanceof AtomicSetBasedOperation) {
            List<Object> vs = ReladomoOperationAccess.inValues((AtomicSetBasedOperation) p, attr);
            return inClause("#" + n, vs, expr, false);
        }
        if (ReladomoOperationAccess.isNotIn(p) && p instanceof AtomicSetBasedOperation) {
            List<Object> vs = ReladomoOperationAccess.inValues((AtomicSetBasedOperation) p, attr);
            return inClause("#" + n, vs, expr, true);
        }
        if (ReladomoOperationAccess.isStartsWith(p) && p instanceof StringLikeOperation) {
            String param = ReladomoOperationAccess.likeParameter((StringLikeOperation) p);
            return "begins_with(#" + n + ", " + expr.value(ExpressionValue.s(param)) + ")";
        }
        if (ReladomoOperationAccess.isContains(p) && p instanceof StringLikeOperation) {
            String param = ReladomoOperationAccess.likeParameter((StringLikeOperation) p);
            return "contains(#" + n + ", " + expr.value(ExpressionValue.s(param)) + ")";
        }
        if (ReladomoOperationAccess.isNotStartsWith(p) && p instanceof StringLikeOperation) {
            String param = ReladomoOperationAccess.likeParameter((StringLikeOperation) p);
            return "NOT begins_with(#" + n + ", " + expr.value(ExpressionValue.s(param)) + ")";
        }
        if (ReladomoOperationAccess.isNotContains(p) && p instanceof StringLikeOperation) {
            String param = ReladomoOperationAccess.likeParameter((StringLikeOperation) p);
            return "NOT contains(#" + n + ", " + expr.value(ExpressionValue.s(param)) + ")";
        }
        if (ReladomoOperationAccess.isLike(p) && p instanceof StringLikeOperation) {
            String pattern = ReladomoOperationAccess.likeParameter((StringLikeOperation) p);
            if (pattern.endsWith("%") && pattern.indexOf('%') == pattern.length() - 1 && pattern.indexOf('_') < 0) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                return "begins_with(#" + n + ", " + expr.value(ExpressionValue.s(prefix)) + ")";
            }
            if (pattern.startsWith("%") && pattern.indexOf('%') == 0 && pattern.indexOf('_') < 0
                    && pattern.lastIndexOf('%') == 0) {
                return "contains(#" + n + ", " + expr.value(ExpressionValue.s(pattern.substring(1))) + ")";
            }
            if (pattern.startsWith("%") && pattern.endsWith("%") && pattern.indexOf('_') < 0
                    && pattern.indexOf('%', 1) == pattern.length() - 1) {
                String mid = pattern.substring(1, pattern.length() - 1);
                return "contains(#" + n + ", " + expr.value(ExpressionValue.s(mid)) + ")";
            }
        }
        return null;
    }

    private static String inClause(String attr, List<Object> vs, Expr expr, boolean not) {
        StringBuilder sb = new StringBuilder();
        if (not) {
            sb.append("NOT ");
        }
        sb.append(attr).append(" IN (");
        for (int i = 0; i < vs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(expr.value(toValue(vs.get(i))));
        }
        sb.append(")");
        return sb.toString();
    }

    private static ExpressionValue toValue(Object v) {
        if (v == null) {
            return ExpressionValue.nul();
        }
        if (v instanceof Timestamp) {
            return ExpressionValue.s(TemporalEncoder.encode((Timestamp) v));
        }
        if (v instanceof Boolean) {
            return ExpressionValue.bool(((Boolean) v).booleanValue());
        }
        if (v instanceof Double || v instanceof Float || v instanceof BigDecimal) {
            throw plan012(v);
        }
        if (v instanceof Number) {
            return ExpressionValue.n(v.toString());
        }
        if (v instanceof byte[]) {
            return ExpressionValue.b((byte[]) v);
        }
        return ExpressionValue.s(String.valueOf(v));
    }

    private static ResidualPredicate residualOf(List<Operation> ops) {
        if (ops == null || ops.isEmpty()) {
            return ResidualPredicate.empty();
        }
        Operation acc = ops.get(0);
        for (int i = 1; i < ops.size(); i++) {
            acc = acc.and(ops.get(i));
        }
        return new ResidualPredicate(acc);
    }

    private static ResidualPredicate andResidual(ResidualPredicate a, ResidualPredicate b) {
        if (a == null || a.isEmpty()) {
            return b == null ? ResidualPredicate.empty() : b;
        }
        if (b == null || b.isEmpty()) {
            return a;
        }
        return new ResidualPredicate(a.operation().and(b.operation()));
    }

    private static Timestamp asTimestamp(Object v) {
        return v instanceof Timestamp ? (Timestamp) v : null;
    }

    private static Object notEqParameter(Operation op) {
        if (op instanceof AtomicNotEqualityOperation) {
            return ((AtomicNotEqualityOperation) op).getParameterAsObject();
        }
        if (op instanceof AtomicEqualityOperation) {
            return ((AtomicEqualityOperation) op).getParameterAsObject();
        }
        return null;
    }

    static boolean valuesEqual(Object a, Object b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return false;
    }

    static int compareValues(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        }
        if (a instanceof Timestamp && b instanceof Timestamp) {
            return ((Timestamp) a).compareTo((Timestamp) b);
        }
        if (a instanceof Comparable && b != null && a.getClass().isInstance(b)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> ca = (Comparable<Object>) a;
            return ca.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    static boolean satisfiesRange(Object value, Operation range) {
        if (!(range instanceof RangeOperation)) {
            return true;
        }
        Object bound = ReladomoOperationAccess.rangeParameter((RangeOperation) range);
        int cmp = compareValues(value, bound);
        if (ReladomoOperationAccess.isGreaterThanEquals(range)) {
            return cmp >= 0;
        }
        if (ReladomoOperationAccess.isGreaterThan(range)) {
            return cmp > 0;
        }
        if (ReladomoOperationAccess.isLessThanEquals(range)) {
            return cmp <= 0;
        }
        if (ReladomoOperationAccess.isLessThan(range)) {
            return cmp < 0;
        }
        return true;
    }

    static boolean listContains(List<Object> vs, Object v) {
        if (vs == null) {
            return false;
        }
        for (int i = 0; i < vs.size(); i++) {
            if (valuesEqual(vs.get(i), v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCommonCompletePk(Node tree, PhysicalDesign design) {
        List<Node> orKids = new ArrayList<Node>();
        collectOr(tree, orKids);
        if (orKids.isEmpty()) {
            return Conjunction.classify(Node.atoms(tree), design).completePk();
        }
        return false;
    }

    private static void collectOr(Node n, List<Node> out) {
        if (n.type == Node.Type.OR) {
            out.add(n);
        }
        if (n.kids != null) {
            for (int i = 0; i < n.kids.size(); i++) {
                collectOr(n.kids.get(i), out);
            }
        }
    }

    /**
     * Copies public {@link PlannerConfig} safeguards onto every plan a finder produces.
     * Hand-built plans keep builder defaults (0 = unbounded) so existing executor tests stay
     * explicit about the limit they set.
     */
    private static void applyConfigLimits(QueryPlan.Builder b, PlannerConfig config) {
        b.maxPages(config.maxPages())
                .inMemoryRowCeiling(config.inMemoryRowCeiling())
                .pageSize(config.pageSize())
                // Not a limit, but it travels the same path and for the same reason: finding 20 was
                // avgItemBytes being settable, stored in two classes, and read by nothing.
                .avgItemBytes(config.avgItemBytes());
    }

    /**
     * Product of IN/eq domain sizes. Checked <em>before</em> {@link #cartesianMaps} so hitting
     * {@code pkFanOutLimit} does not first allocate the combination list the limit exists to prevent.
     */
    private static long cartesianProductSize(Map<String, List<Object>> domains) {
        long product = 1L;
        for (List<Object> vs : domains.values()) {
            int n = vs.size();
            if (n == 0) {
                return 0L;
            }
            if (product > Long.MAX_VALUE / n) {
                return Long.MAX_VALUE;
            }
            product *= n;
        }
        return product;
    }

    private static void refuseIfCartesianExceeds(Map<String, List<Object>> domains,
                                                 PlannerConfig config, PhysicalDesign design,
                                                 Operation whole) {
        long product = cartesianProductSize(domains);
        if (product > config.pkFanOutLimit()) {
            int n = product > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) product;
            throw plan002(n, config.pkFanOutLimit(), design.className(), whole);
        }
    }

    private static List<Map<String, Object>> cartesianMaps(Map<String, List<Object>> domains) {
        List<Map<String, Object>> acc = new ArrayList<Map<String, Object>>();
        acc.add(new LinkedHashMap<String, Object>());
        for (Map.Entry<String, List<Object>> e : domains.entrySet()) {
            List<Map<String, Object>> next = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < acc.size(); i++) {
                Map<String, Object> prefix = acc.get(i);
                List<Object> vs = e.getValue();
                for (int j = 0; j < vs.size(); j++) {
                    Map<String, Object> copy = new LinkedHashMap<String, Object>(prefix);
                    copy.put(e.getKey(), vs.get(j));
                    next.add(copy);
                }
            }
            acc = next;
        }
        return acc;
    }

    static ReladynamoUnplannableOperationException plan002(int n, int limit, String className, Operation op) {
        return new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-002: Partition-key IN/OR fan-out of " + n
                        + " exceeds pkFanOutLimit=" + limit + " for " + className
                        + ". Narrow the operation, raise query.pkFanOutLimit, or split the call. Operation: "
                        + ReladomoOperationAccess.dump(op));
    }

    static ReladynamoUnplannableOperationException plan010(String className) {
        return new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-010: computeFunction is not supported on DynamoDB (raw SQL expression). Class: "
                        + className + ".");
    }

    static ReladynamoUnplannableOperationException plan011(String className) {
        return new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-011: TupleExistsOperation / temp-tuple queries are not supported by Reladynamo. Class: "
                        + className + ".");
    }

    static ReladynamoUnplannableOperationException plan012(Object value) {
        String type = value == null ? "null" : value.getClass().getName();
        return new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-012: Double/Float/BigDecimal cannot be emitted as DynamoDB N; "
                        + "IEEE-754 B and decimal S are not ordered as N. The planner must leave these "
                        + "predicates as typed residuals after decode. value class=" + type);
    }

    static ReladynamoUnplannableOperationException plan012Key(String className, String javaName) {
        return new ReladynamoUnplannableOperationException(
                "RELADYNAMO-PLAN-012: Float/Double cannot be a partition or sort key component for "
                        + className + "." + javaName
                        + "; KeyComponentEncoder refuses IEEE types and residual cannot invent a key. "
                        + "Use an integral, string, or BigDecimal key, or filter the attribute as payload.");
    }

    private static boolean isIeeeOrDecimalPayload(Operation p, PhysicalDesign design) {
        if (ReladomoOperationAccess.isIeeeOrDecimalOperation(p)) {
            return true;
        }
        String javaName = ReladomoOperationAccess.attributeJavaName(p);
        if (javaName == null) {
            return false;
        }
        try {
            return ReladomoOperationAccess.isIeeeOrDecimalJavaType(
                    design.entity().attribute(javaName).javaType());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // --- expression accumulator ---

    static final class Expr {
        private final List<String> tokens = new ArrayList<String>();
        private final Map<String, String> names = new LinkedHashMap<String, String>();
        private final Map<String, ExpressionValue> values = new LinkedHashMap<String, ExpressionValue>();
        private final Map<String, String> nameByAttr = new LinkedHashMap<String, String>();
        private int nameSeq;
        private int valueSeq;

        String name(String attributeName) {
            String existing = nameByAttr.get(attributeName);
            if (existing != null) {
                return existing;
            }
            String alias = sanitizeName(attributeName);
            if (names.containsKey("#" + alias)) {
                alias = alias + "_" + (nameSeq++);
            }
            nameByAttr.put(attributeName, alias);
            names.put("#" + alias, attributeName);
            return alias;
        }

        private String sanitizeName(String attributeName) {
            if (attributeName == null || attributeName.isEmpty()) {
                return "n" + (nameSeq++);
            }
            StringBuilder sb = new StringBuilder(attributeName.length());
            for (int i = 0; i < attributeName.length(); i++) {
                char c = attributeName.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    sb.append(c);
                } else {
                    sb.append('_');
                }
            }
            if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) {
                sb.insert(0, 'n');
            }
            return sb.toString();
        }

        String value(ExpressionValue v) {
            String alias = "v" + (valueSeq++);
            values.put(":" + alias, v);
            return ":" + alias;
        }

        void addToken(String token) {
            tokens.add(token);
        }

        FilterExpression toFilter() {
            if (tokens.isEmpty()) {
                return FilterExpression.empty();
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) {
                    sb.append(" AND ");
                }
                sb.append(tokens.get(i));
            }
            return new FilterExpression(sb.toString(), names, values);
        }
    }

    // --- conjunction classification ---

    static final class Conjunction {
        final Map<String, Object> pkEq = new LinkedHashMap<String, Object>();
        final Map<String, List<Object>> pkIn = new LinkedHashMap<String, List<Object>>();
        final Map<String, List<Operation>> pkOps = new LinkedHashMap<String, List<Operation>>();
        final Map<String, Object> fromEq = new LinkedHashMap<String, Object>();
        final Map<String, Object> payloadEq = new LinkedHashMap<String, Object>();
        final Map<String, List<Object>> payloadIn = new LinkedHashMap<String, List<Object>>();
        final List<Operation> payload = new ArrayList<Operation>();
        final List<Operation> keptOr = new ArrayList<Operation>();
        Timestamp businessAsOf;
        Timestamp processingAsOf;
        AsOfAttribute businessAsOfAttr;
        AsOfAttribute processingAsOfAttr;
        Timestamp businessEdge;
        Timestamp processingEdge;
        Timestamp businessFromLo;
        Timestamp businessFromHi;
        boolean isAll;
        boolean isNone;
        boolean hasMapped;
        ReladynamoUnplannableOperationException rejected;

        boolean completePk() {
            return false;
        }

        static Conjunction classify(List<Operation> atoms, PhysicalDesign design) {
            Conjunction c = new Conjunction();
            for (int i = 0; i < atoms.size(); i++) {
                classifyOne(atoms.get(i), design, c);
            }
            finalizePk(c, design);
            return c;
        }

        void addPkOp(String javaName, Operation op) {
            List<Operation> list = pkOps.get(javaName);
            if (list == null) {
                list = new ArrayList<Operation>();
                pkOps.put(javaName, list);
            }
            list.add(op);
        }

        /**
         * A key condition that consumes an attribute must not discard sibling predicates
         * on that same attribute. Intersect them; if unsatisfiable, the plan is empty.
         */
        static void finalizePk(Conjunction c, PhysicalDesign design) {
            if (c.isNone) {
                return;
            }
            List<AttributeMapping> pks = design.primaryKeyAttributes();
            for (int i = 0; i < pks.size(); i++) {
                String name = pks.get(i).javaName();
                List<Operation> ops = c.pkOps.get(name);
                if (ops == null || ops.isEmpty()) {
                    continue;
                }
                PkDomain domain = PkDomain.unbounded();
                List<Operation> eqsAndIns = new ArrayList<Operation>();
                List<Operation> others = new ArrayList<Operation>();
                for (int j = 0; j < ops.size(); j++) {
                    Operation op = ops.get(j);
                    if (ReladomoOperationAccess.isEquality(op) && op instanceof AtomicEqualityOperation) {
                        eqsAndIns.add(op);
                        domain = domain.intersectEq(((AtomicEqualityOperation) op).getParameterAsObject());
                    } else if (ReladomoOperationAccess.isIn(op) && op instanceof AtomicSetBasedOperation) {
                        eqsAndIns.add(op);
                        List<Object> vs = ReladomoOperationAccess.inValues(
                                (AtomicSetBasedOperation) op, ReladomoOperationAccess.attributeOf(op));
                        domain = domain.intersectIn(vs);
                    } else {
                        others.add(op);
                    }
                    if (domain.isEmpty()) {
                        c.isNone = true;
                        return;
                    }
                }
                for (int j = 0; j < others.size(); j++) {
                    Operation op = others.get(j);
                    if (ReladomoOperationAccess.isNotEquality(op)) {
                        domain = domain.exclude(notEqParameter(op));
                    } else if (ReladomoOperationAccess.isNotIn(op) && op instanceof AtomicSetBasedOperation) {
                        List<Object> vs = ReladomoOperationAccess.inValues(
                                (AtomicSetBasedOperation) op, ReladomoOperationAccess.attributeOf(op));
                        domain = domain.excludeAll(vs);
                    } else if (ReladomoOperationAccess.isRange(op)) {
                        domain = domain.intersectRange(op);
                    }
                    if (domain.isEmpty()) {
                        c.isNone = true;
                        return;
                    }
                }
                Operation consumed = null;
                if (domain.isSingleton()) {
                    Object v = domain.singleton();
                    c.pkEq.put(name, v);
                    c.pkIn.remove(name);
                    consumed = firstMatchingEquality(eqsAndIns, v);
                    if (consumed == null && !eqsAndIns.isEmpty()) {
                        consumed = eqsAndIns.get(0);
                    }
                } else if (domain.isFinite()) {
                    c.pkEq.remove(name);
                    c.pkIn.put(name, domain.values());
                    if (!eqsAndIns.isEmpty()) {
                        consumed = eqsAndIns.get(0);
                    }
                } else {
                    c.pkEq.remove(name);
                    c.pkIn.remove(name);
                }
                for (int j = 0; j < ops.size(); j++) {
                    Operation op = ops.get(j);
                    if (op != consumed) {
                        c.payload.add(op);
                    }
                }
            }
        }

        private static Operation firstMatchingEquality(List<Operation> eqsAndIns, Object v) {
            for (int i = 0; i < eqsAndIns.size(); i++) {
                Operation op = eqsAndIns.get(i);
                if (ReladomoOperationAccess.isEquality(op) && op instanceof AtomicEqualityOperation) {
                    if (valuesEqual(((AtomicEqualityOperation) op).getParameterAsObject(), v)) {
                        return op;
                    }
                }
            }
            return null;
        }

        private static void classifyOne(Operation op, PhysicalDesign design, Conjunction c) {
            if (ReladomoOperationAccess.isNoOperation(op)) {
                return;
            }
            if (ReladomoOperationAccess.isNone(op)) {
                c.isNone = true;
                return;
            }
            if (ReladomoOperationAccess.isAll(op)) {
                c.isAll = true;
                return;
            }
            if (ReladomoOperationAccess.isTupleExists(op)) {
                c.rejected = plan011(design.className());
                return;
            }
            if (ReladomoOperationAccess.isAsOfInfiniteNull(op)) {
                c.rejected = new ReladynamoUnplannableOperationException(
                        "RELADYNAMO-PLAN-005: No DynamoDB access path for " + design.className()
                                + ": AsOfEqInfiniteNullOperation is rejected (keys cannot be null). Operation: "
                                + ReladomoOperationAccess.dump(op));
                return;
            }
            if (ReladomoOperationAccess.isMapped(op) || ReladomoOperationAccess.isNotExists(op)) {
                c.hasMapped = true;
                c.payload.add(op);
                return;
            }
            if (op instanceof MappedOperation) {
                c.hasMapped = true;
                c.payload.add(op);
                return;
            }
            if (ReladomoOperationAccess.isAsOfEq(op)) {
                AsOfEqOperation asOf = (AsOfEqOperation) op;
                AsOfAttribute attr = (AsOfAttribute) asOf.getAttribute();
                Timestamp param = asOf.getParameter();
                if (attr.isProcessingDate()) {
                    c.processingAsOf = param;
                    c.processingAsOfAttr = attr;
                } else {
                    c.businessAsOf = param;
                    c.businessAsOfAttr = attr;
                }
                return;
            }
            if (ReladomoOperationAccess.isAsOfEdge(op)) {
                AsOfAttribute attr = (AsOfAttribute) ReladomoOperationAccess.attributeOf(op);
                if (attr != null && attr.isProcessingDate()) {
                    c.processingEdge = asTimestamp(((AtomicEqualityOperation) op).getParameterAsObject());
                } else {
                    c.businessEdge = asTimestamp(((AtomicEqualityOperation) op).getParameterAsObject());
                }
                return;
            }

            String javaName = ReladomoOperationAccess.attributeJavaName(op);
            if (javaName == null) {
                c.payload.add(op);
                return;
            }

            if (design.isPrimaryKeyJavaName(javaName)) {
                Attribute pkAttr = ReladomoOperationAccess.attributeOf(op);
                if (ReladomoOperationAccess.isFloatOrDoubleAttribute(pkAttr)) {
                    c.rejected = plan012Key(design.className(), javaName);
                    return;
                }
                if (ReladomoOperationAccess.isIeeeOrDecimalOperation(op)
                        && !ReladomoOperationAccess.isEquality(op)
                        && !ReladomoOperationAccess.isIn(op)
                        && !ReladomoOperationAccess.isIsNull(op)
                        && !ReladomoOperationAccess.isIsNotNull(op)) {
                    // BigDecimal PK equality/IN still uses KeyComponentEncoder (out of scope).
                    // Range/inequality cannot be a DynamoDB key condition: decimal-string
                    // order is not numeric order. Residual after decode if the rest of the
                    // key is known; otherwise the usual scan/PLAN-001 path applies.
                    c.payload.add(op);
                    return;
                }
                c.addPkOp(javaName, op);
                return;
            }

            if (ReladomoOperationAccess.isEquality(op) && op instanceof AtomicEqualityOperation) {
                Object v = ((AtomicEqualityOperation) op).getParameterAsObject();
                if (design.isFromColumnJavaName(javaName)) {
                    c.fromEq.put(javaName, v);
                    return;
                }
                c.payloadEq.put(javaName, v);
                c.payload.add(op);
                return;
            }

            if (ReladomoOperationAccess.isIn(op) && op instanceof AtomicSetBasedOperation) {
                List<Object> vs = ReladomoOperationAccess.inValues(
                        (AtomicSetBasedOperation) op, ReladomoOperationAccess.attributeOf(op));
                c.payloadIn.put(javaName, vs);
                c.payload.add(op);
                return;
            }

            if (ReladomoOperationAccess.isRange(op) && javaName.equals(design.businessFromJavaName())
                    && op instanceof RangeOperation) {
                Object v = ReladomoOperationAccess.rangeParameter((RangeOperation) op);
                Timestamp ts = asTimestamp(v);
                if (ts == null && v instanceof Number) {
                    ts = null;
                }
                if (ReladomoOperationAccess.isGreaterThan(op) || ReladomoOperationAccess.isGreaterThanEquals(op)) {
                    c.businessFromLo = ts;
                } else {
                    c.businessFromHi = ts;
                }
                c.payload.add(op);
                return;
            }

            c.payload.add(op);
        }
    }

    /**
     * Remaining values for one partition-key attribute after intersecting every predicate
     * on that attribute. Unbounded means no equality/IN has bound it yet.
     */
    static final class PkDomain {
        private boolean unbounded;
        private boolean empty;
        private List<Object> values;

        static PkDomain unbounded() {
            PkDomain d = new PkDomain();
            d.unbounded = true;
            return d;
        }

        boolean isEmpty() {
            return empty;
        }

        boolean isSingleton() {
            return !unbounded && !empty && values != null && values.size() == 1;
        }

        boolean isFinite() {
            return !unbounded && !empty && values != null && !values.isEmpty();
        }

        Object singleton() {
            return values.get(0);
        }

        List<Object> values() {
            return values;
        }

        PkDomain intersectEq(Object v) {
            if (empty) {
                return this;
            }
            if (unbounded) {
                return finiteSingleton(v);
            }
            if (listContains(values, v)) {
                return finiteSingleton(v);
            }
            return emptyDomain();
        }

        PkDomain intersectIn(List<Object> vs) {
            if (empty) {
                return this;
            }
            if (vs == null || vs.isEmpty()) {
                return emptyDomain();
            }
            if (unbounded) {
                PkDomain d = new PkDomain();
                d.values = unique(vs);
                if (d.values.isEmpty()) {
                    d.empty = true;
                }
                return d;
            }
            List<Object> kept = new ArrayList<Object>();
            for (int i = 0; i < values.size(); i++) {
                if (listContains(vs, values.get(i))) {
                    kept.add(values.get(i));
                }
            }
            return ofKept(kept);
        }

        PkDomain exclude(Object v) {
            if (empty || unbounded) {
                return this;
            }
            List<Object> kept = new ArrayList<Object>();
            for (int i = 0; i < values.size(); i++) {
                if (!valuesEqual(values.get(i), v)) {
                    kept.add(values.get(i));
                }
            }
            return ofKept(kept);
        }

        PkDomain excludeAll(List<Object> vs) {
            if (empty || unbounded || vs == null) {
                return this;
            }
            List<Object> kept = new ArrayList<Object>();
            for (int i = 0; i < values.size(); i++) {
                if (!listContains(vs, values.get(i))) {
                    kept.add(values.get(i));
                }
            }
            return ofKept(kept);
        }

        PkDomain intersectRange(Operation range) {
            if (empty || unbounded) {
                return this;
            }
            List<Object> kept = new ArrayList<Object>();
            for (int i = 0; i < values.size(); i++) {
                if (satisfiesRange(values.get(i), range)) {
                    kept.add(values.get(i));
                }
            }
            return ofKept(kept);
        }

        private static PkDomain finiteSingleton(Object v) {
            PkDomain d = new PkDomain();
            d.values = new ArrayList<Object>(1);
            d.values.add(v);
            return d;
        }

        private static PkDomain emptyDomain() {
            PkDomain d = new PkDomain();
            d.empty = true;
            return d;
        }

        private static PkDomain ofKept(List<Object> kept) {
            PkDomain d = new PkDomain();
            if (kept.isEmpty()) {
                d.empty = true;
            } else {
                d.values = kept;
            }
            return d;
        }

        private static List<Object> unique(List<Object> vs) {
            List<Object> out = new ArrayList<Object>();
            for (int i = 0; i < vs.size(); i++) {
                if (!listContains(out, vs.get(i))) {
                    out.add(vs.get(i));
                }
            }
            return out;
        }
    }

    // --- tree / DNF ---

    static final class DnfExplosion extends RuntimeException {
        final int clauseCount;

        DnfExplosion(int clauseCount) {
            this.clauseCount = clauseCount;
        }
    }

    static final class Node {
        enum Type { AND, OR, ATOM, ALL, NONE, TUPLE }

        final Type type;
        final List<Node> kids;
        final Operation atom;

        Node(Type type, List<Node> kids, Operation atom) {
            this.type = type;
            this.kids = kids;
            this.atom = atom;
        }

        static Node atom(Operation op) {
            if (ReladomoOperationAccess.isNone(op)) {
                return new Node(Type.NONE, null, op);
            }
            if (ReladomoOperationAccess.isAll(op)) {
                return new Node(Type.ALL, null, op);
            }
            if (ReladomoOperationAccess.isTupleExists(op)) {
                return new Node(Type.TUPLE, null, op);
            }
            return new Node(Type.ATOM, null, op);
        }

        static Node decompose(Operation op) {
            if (ReladomoOperationAccess.isNoOperation(op)) {
                return new Node(Type.AND, Collections.emptyList(), op);
            }
            if (op instanceof com.gs.fw.common.mithra.finder.AndOperation
                    || op instanceof com.gs.fw.common.mithra.finder.MultiEqualityOperation) {
                List<Operation> ops = ReladomoOperationAccess.operands(op);
                List<Node> kids = new ArrayList<Node>(ops.size());
                for (int i = 0; i < ops.size(); i++) {
                    kids.add(decompose(ops.get(i)));
                }
                return new Node(Type.AND, kids, op);
            }
            if (op instanceof com.gs.fw.common.mithra.finder.OrOperation) {
                List<Operation> ops = ReladomoOperationAccess.orOperands(op);
                List<Node> kids = new ArrayList<Node>(ops.size());
                for (int i = 0; i < ops.size(); i++) {
                    kids.add(decompose(ops.get(i)));
                }
                return new Node(Type.OR, kids, op);
            }
            return atom(op);
        }

        static Node normalize(Node n) {
            if (n.kids == null || n.kids.isEmpty()) {
                return n;
            }
            List<Node> kids = new ArrayList<Node>();
            for (int i = 0; i < n.kids.size(); i++) {
                Node k = normalize(n.kids.get(i));
                if (k.type == Type.AND && n.type == Type.AND) {
                    kids.addAll(k.kids == null ? Collections.singletonList(k) : k.kids);
                } else if (k.type == Type.OR && n.type == Type.OR) {
                    kids.addAll(k.kids == null ? Collections.singletonList(k) : k.kids);
                } else if (n.type == Type.AND && k.type == Type.ALL) {
                    continue;
                } else if (n.type == Type.OR && k.type == Type.NONE) {
                    continue;
                } else if (n.type == Type.AND && k.type == Type.NONE) {
                    return new Node(Type.NONE, null, k.atom);
                } else if (n.type == Type.OR && k.type == Type.ALL) {
                    return k;
                } else {
                    kids.add(k);
                }
            }
            if (kids.isEmpty()) {
                return n.type == Type.OR
                        ? new Node(Type.NONE, null, n.atom)
                        : new Node(Type.AND, Collections.emptyList(), n.atom);
            }
            if (kids.size() == 1) {
                return kids.get(0);
            }
            return new Node(n.type, kids, n.atom);
        }

        static List<List<Operation>> toDnf(Node n, int cap) {
            List<List<Operation>> dnf = toDnfRec(n, cap);
            if (dnf.size() > cap) {
                throw new DnfExplosion(dnf.size());
            }
            return dnf;
        }

        private static List<List<Operation>> toDnfRec(Node n, int cap) {
            if (n.type == Type.ATOM || n.type == Type.ALL || n.type == Type.NONE || n.type == Type.TUPLE) {
                return Collections.singletonList(Collections.singletonList(n.atom));
            }
            if (n.type == Type.OR) {
                List<List<Operation>> out = new ArrayList<List<Operation>>();
                for (int i = 0; i < n.kids.size(); i++) {
                    out.addAll(toDnfRec(n.kids.get(i), cap));
                    if (out.size() > cap) {
                        throw new DnfExplosion(out.size());
                    }
                }
                return out;
            }
            List<List<Operation>> acc = Collections.singletonList(new ArrayList<Operation>());
            for (int i = 0; i < n.kids.size(); i++) {
                List<List<Operation>> right = toDnfRec(n.kids.get(i), cap);
                List<List<Operation>> next = new ArrayList<List<Operation>>();
                for (int a = 0; a < acc.size(); a++) {
                    for (int b = 0; b < right.size(); b++) {
                        List<Operation> merged = new ArrayList<Operation>(acc.get(a));
                        merged.addAll(right.get(b));
                        next.add(merged);
                        if (next.size() > cap) {
                            throw new DnfExplosion(next.size());
                        }
                    }
                }
                acc = next;
            }
            return acc;
        }

        static List<Operation> atoms(Node n) {
            List<Operation> out = new ArrayList<Operation>();
            collectAtoms(n, out);
            return out;
        }

        private static void collectAtoms(Node n, List<Operation> out) {
            if (n.atom != null && (n.type == Type.ATOM || n.type == Type.ALL || n.type == Type.NONE
                    || n.type == Type.TUPLE)) {
                out.add(n.atom);
            }
            if (n.kids != null) {
                for (int i = 0; i < n.kids.size(); i++) {
                    collectAtoms(n.kids.get(i), out);
                }
            }
        }

        boolean containsTupleExists() {
            if (type == Type.TUPLE) {
                return true;
            }
            if (kids != null) {
                for (int i = 0; i < kids.size(); i++) {
                    if (kids.get(i).containsTupleExists()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
