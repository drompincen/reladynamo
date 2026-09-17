package io.reladynamo.ddb.exec;

import io.reladynamo.core.plan.ExpressionValue;
import io.reladynamo.core.plan.FilterExpression;
import io.reladynamo.core.plan.KeyCondition;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.ResidualPredicate;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collapses a {@code QUERY_FAN_OUT} of same-shape Queries into one PartiQL
 * {@code SELECT ... WHERE pk IN [?, ?]} so Reladomo's {@code IN} of parent ids
 * is one round trip, not one {@code query} per value.
 *
 * <p>DynamoDB's Query API cannot put {@code IN} on a partition key; PartiQL
 * {@code ExecuteStatement} can, and the service runs one Query per value
 * internally. That is still not a Scan: every value is a bound partition key.
 */
final class FanOutSelect {

    /** PartiQL IN lists are capped; chunk above this. */
    static final int IN_CHUNK = 50;

    final String statement;
    final List<AttributeValue> parameters;
    final boolean consistentRead;
    final ResidualPredicate residual;

    private FanOutSelect(String statement, List<AttributeValue> parameters,
                         boolean consistentRead, ResidualPredicate residual) {
        this.statement = statement;
        this.parameters = parameters;
        this.consistentRead = consistentRead;
        this.residual = residual == null ? ResidualPredicate.empty() : residual;
    }

    static List<FanOutSelect> tryCollapse(List<QueryPlan> children) {
        return tryCollapse(children, IN_CHUNK);
    }

    static List<FanOutSelect> tryCollapse(List<QueryPlan> children, int chunkSize) {
        if (children == null || children.size() < 2) {
            return Collections.emptyList();
        }
        int cap = chunkSize > 0 ? Math.min(IN_CHUNK, chunkSize) : IN_CHUNK;
        QueryPlan first = children.get(0);
        KeyCondition firstKey = first.keyCondition();
        if (firstKey == null || firstKey.encodedPartitionKey() == null) {
            return Collections.emptyList();
        }
        if (first.kind() != PlanKind.QUERY && first.kind() != PlanKind.GET_ITEM) {
            return Collections.emptyList();
        }
        String pkAttr = firstKey.names().get("#pk");
        if (pkAttr == null) {
            return Collections.emptyList();
        }
        String table = first.tableName();
        String index = first.indexName();
        String filterExpr = first.filterExpression();
        String skTail = skTail(firstKey);
        String sharedSk = firstKey.encodedSortKey();

        List<String> pks = new ArrayList<String>(children.size());
        ResidualPredicate residual = first.residual();
        for (int i = 0; i < children.size(); i++) {
            QueryPlan child = children.get(i);
            if (child.kind() != PlanKind.QUERY && child.kind() != PlanKind.GET_ITEM) {
                return Collections.emptyList();
            }
            if (!table.equals(child.tableName())) {
                return Collections.emptyList();
            }
            if (!indexEquals(index, child.indexName())) {
                return Collections.emptyList();
            }
            KeyCondition key = child.keyCondition();
            if (key == null || key.encodedPartitionKey() == null) {
                return Collections.emptyList();
            }
            if (!pkAttr.equals(key.names().get("#pk"))) {
                return Collections.emptyList();
            }
            if (!Objects.equals(filterExpr, child.filterExpression())) {
                return Collections.emptyList();
            }
            if (!Objects.equals(skTail, skTail(key))) {
                return Collections.emptyList();
            }
            if (!Objects.equals(sharedSk, key.encodedSortKey())) {
                // Distinct sort keys cannot share a PK IN (would over-read).
                return Collections.emptyList();
            }
            // Expression text is not enough: children can share `#LABEL = :v0` while
            // binding different values, mapping `#LABEL` to different attributes, or
            // carrying different residuals. Collapsing those to the first child's
            // predicate changes OR-branch semantics (R-06).
            if (!sameFilterNames(first, child) || !sameFilterValues(first, child)) {
                return Collections.emptyList();
            }
            if (!sameSkBindings(firstKey, key)) {
                return Collections.emptyList();
            }
            if (!sameResidual(residual, child.residual())) {
                return Collections.emptyList();
            }
            pks.add(key.encodedPartitionKey());
        }

        Converted filter = Converted.empty();
        if (filterExpr != null && !filterExpr.isEmpty()) {
            FilterExpression fe = first.filter();
            filter = convert(filterExpr, mergeNames(first), fe.values().isEmpty()
                    ? first.expressionAttributeValues()
                    : fe.values());
            if (filter == null) {
                return Collections.emptyList();
            }
        }
        Converted sk = Converted.empty();
        if (skTail != null) {
            sk = convert(skTail, firstKey.names(), firstKey.values());
            if (sk == null) {
                return Collections.emptyList();
            }
        }

        boolean consistent = first.consistentRead();
        if (!isPrimary(index)) {
            consistent = false;
        }

        List<FanOutSelect> chunks = new ArrayList<FanOutSelect>();
        int from = 0;
        while (from < pks.size()) {
            int to = Math.min(from + cap, pks.size());
            chunks.add(buildChunk(table, index, pkAttr, pks.subList(from, to),
                    sk, filter, consistent, residual));
            from = to;
        }
        return chunks;
    }

    private static FanOutSelect buildChunk(String table, String index, String pkAttr,
                                           List<String> pks, Converted sk, Converted filter,
                                           boolean consistent, ResidualPredicate residual) {
        StringBuilder sql = new StringBuilder(128 + pks.size() * 4);
        sql.append("SELECT * FROM ").append(quotedFrom(table, index));
        sql.append(" WHERE ").append(quoteIdent(pkAttr)).append(" IN [");
        List<AttributeValue> params = new ArrayList<AttributeValue>(pks.size() + 8);
        for (int i = 0; i < pks.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
            params.add(AttributeValue.builder().s(pks.get(i)).build());
        }
        sql.append(']');
        if (!sk.isEmpty()) {
            sql.append(" AND ").append(sk.sql);
            params.addAll(sk.params);
        }
        if (!filter.isEmpty()) {
            sql.append(" AND ").append(filter.sql);
            params.addAll(filter.params);
        }
        return new FanOutSelect(sql.toString(), params, consistent, residual);
    }

    private static String quotedFrom(String table, String index) {
        if (isPrimary(index)) {
            return quoteIdent(table);
        }
        return quoteIdent(table) + "." + quoteIdent(index);
    }

    static boolean isPrimary(String index) {
        return index == null || index.isEmpty() || PhysicalDesign.PRIMARY_INDEX.equals(index);
    }

    private static boolean indexEquals(String a, String b) {
        if (isPrimary(a)) {
            return isPrimary(b);
        }
        return a.equals(b);
    }

    private static String skTail(KeyCondition key) {
        String expr = key.expression();
        if (expr == null) {
            return null;
        }
        int and = expr.indexOf(" AND ");
        if (and < 0) {
            return null;
        }
        return expr.substring(and + 5);
    }

    private static Map<String, String> mergeNames(QueryPlan plan) {
        return plan.expressionAttributeNames();
    }

    /**
     * Filter name maps must match so {@code #ATTR = :v0} cannot collapse
     * {@code name} and {@code status} onto the first child's attribute.
     */
    private static boolean sameFilterNames(QueryPlan a, QueryPlan b) {
        return Objects.equals(filterNames(a), filterNames(b));
    }

    /**
     * Filter value bindings must match so {@code #LABEL = :v0} with {@code :v0="A"}
     * cannot be shared with a sibling whose {@code :v0} is {@code "B"}.
     */
    private static boolean sameFilterValues(QueryPlan a, QueryPlan b) {
        return Objects.equals(filterValues(a), filterValues(b));
    }

    /**
     * Sort-key tail names/values except {@code :pk}/{@code #pk}. Equal-looking
     * {@code BETWEEN :sklo AND :skhi} expressions with different dates must not collapse.
     */
    private static boolean sameSkBindings(KeyCondition a, KeyCondition b) {
        return Objects.equals(withoutKey(a.names(), "#pk"), withoutKey(b.names(), "#pk"))
                && Objects.equals(withoutKey(a.values(), ":pk"), withoutKey(b.values(), ":pk"));
    }

    private static boolean sameResidual(ResidualPredicate a, ResidualPredicate b) {
        boolean aEmpty = a == null || a.isEmpty();
        boolean bEmpty = b == null || b.isEmpty();
        if (aEmpty && bEmpty) {
            return true;
        }
        if (aEmpty || bEmpty) {
            return false;
        }
        if (Objects.equals(a.operation(), b.operation())) {
            return true;
        }
        // Reladomo Operation.equals is not always value equality; dump() is the
        // planner-facing semantic fingerprint already used in QueryPlan.toAssertableString().
        String dumpA = a.dump();
        String dumpB = b.dump();
        return dumpA != null && !dumpA.isEmpty() && dumpA.equals(dumpB);
    }

    private static Map<String, String> filterNames(QueryPlan plan) {
        FilterExpression filter = plan.filter();
        if (filter == null || filter.names() == null) {
            return Collections.emptyMap();
        }
        return filter.names();
    }

    private static Map<String, ExpressionValue> filterValues(QueryPlan plan) {
        FilterExpression filter = plan.filter();
        if (filter != null && filter.values() != null && !filter.values().isEmpty()) {
            return filter.values();
        }
        return withoutKey(plan.expressionAttributeValues(), ":pk");
    }

    private static <V> Map<String, V> withoutKey(Map<String, V> map, String key) {
        if (map == null || map.isEmpty()) {
            return Collections.emptyMap();
        }
        if (!map.containsKey(key)) {
            return map;
        }
        Map<String, V> copy = new LinkedHashMap<String, V>(map);
        copy.remove(key);
        return copy;
    }

    /**
     * Turns a DynamoDB condition ({@code #n = :v}) into PartiQL ({@code "n" = ?}).
     */
    static Converted convert(String expression, Map<String, String> names,
                             Map<String, ExpressionValue> values) {
        if (expression == null || expression.isEmpty()) {
            return Converted.empty();
        }
        String expr = expression;
        if (names != null && !names.isEmpty()) {
            List<String> keys = new ArrayList<String>(names.keySet());
            Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    return Integer.compare(b.length(), a.length());
                }
            });
            for (int i = 0; i < keys.size(); i++) {
                String placeholder = keys.get(i);
                String attr = names.get(placeholder);
                expr = expr.replace(placeholder, quoteIdent(attr));
            }
        }
        StringBuilder sql = new StringBuilder(expr.length());
        List<AttributeValue> params = new ArrayList<AttributeValue>();
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == ':' && i + 1 < expr.length() && isIdentStart(expr.charAt(i + 1))) {
                int j = i + 1;
                while (j < expr.length() && isIdentPart(expr.charAt(j))) {
                    j++;
                }
                String token = expr.substring(i, j);
                ExpressionValue v = values == null ? null : values.get(token);
                if (v == null) {
                    return null;
                }
                params.add(ExpressionValues.toSdk(v));
                sql.append('?');
                i = j;
            } else {
                sql.append(c);
                i++;
            }
        }
        return new Converted(sql.toString(), params);
    }

    static String quoteIdent(String ident) {
        if (ident == null) {
            throw new IllegalArgumentException("PartiQL identifier is required");
        }
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static final class Converted {
        final String sql;
        final List<AttributeValue> params;

        Converted(String sql, List<AttributeValue> params) {
            this.sql = sql;
            this.params = params;
        }

        static Converted empty() {
            return new Converted("", Collections.<AttributeValue>emptyList());
        }

        boolean isEmpty() {
            return sql == null || sql.isEmpty();
        }
    }
}
