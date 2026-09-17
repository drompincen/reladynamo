package io.reladynamo.core.plan.eval;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.plan.ExpressionValue;
import io.reladynamo.core.plan.FilterExpression;
import io.reladynamo.core.plan.KeyCondition;
import io.reladynamo.core.plan.PlanKind;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.ReladynamoResidualEvaluationException;
import io.reladynamo.core.plan.ResidualPredicate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure evaluator of a {@link QueryPlan} against an in-memory item. No AWS SDK types.
 *
 * <p>Used as the oracle companion: Reladomo {@code matches} vs interpreter {@code accepts}
 * must agree. A plan that over-selects without a residual that rejects the extra row is a bug.
 */
public final class QueryPlanInterpreter {

    public boolean accepts(QueryPlan plan, Map<String, ExpressionValue> item) {
        return accepts(plan, item, null);
    }

    public boolean accepts(QueryPlan plan, Map<String, ExpressionValue> item, Object residualCandidate) {
        if (plan.kind() == PlanKind.EMPTY) {
            return false;
        }
        if (plan.kind() == PlanKind.QUERY_FAN_OUT) {
            List<QueryPlan> kids = plan.fanOut();
            for (int i = 0; i < kids.size(); i++) {
                if (accepts(kids.get(i), item, residualCandidate)) {
                    return true;
                }
            }
            return false;
        }
        if (plan.kind() != PlanKind.SCAN) {
            if (!keyMatches(plan.keyCondition(), item, plan.expressionAttributeNames(),
                    plan.expressionAttributeValues())) {
                return false;
            }
        }
        if (!filterMatches(plan.filter(), item, plan.expressionAttributeNames(),
                plan.expressionAttributeValues())) {
            return false;
        }
        ResidualPredicate residual = plan.residual();
        if (residual != null && !residual.isEmpty()) {
            if (residualCandidate == null) {
                throw new ReladynamoResidualEvaluationException(
                        "residual present but no candidate object supplied to interpreter. Operation: "
                                + residual.dump());
            }
            return residual.matchesOrThrow(residualCandidate);
        }
        return true;
    }

    /**
     * Filter-only: {@code null} from Reladomo {@code matches} is not a pass. Used by the
     * adversarial oracle when comparing against the original operation.
     */
    public static boolean reladomoPass(Operation op, Object candidate) {
        Boolean result = op.matches(candidate);
        return Boolean.TRUE.equals(result);
    }

    private boolean keyMatches(KeyCondition key, Map<String, ExpressionValue> item,
                               Map<String, String> names, Map<String, ExpressionValue> values) {
        if (key == null || key.isEmpty()) {
            return true;
        }
        return evalBool(key.expression(), item, names, values);
    }

    private boolean filterMatches(FilterExpression filter, Map<String, ExpressionValue> item,
                                  Map<String, String> names, Map<String, ExpressionValue> values) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        return evalBool(filter.expression(), item, names, values);
    }

    boolean evalBool(String expr, Map<String, ExpressionValue> item,
                     Map<String, String> names, Map<String, ExpressionValue> values) {
        Parser p = new Parser(expr, item, names, values);
        boolean v = p.parseOr();
        p.skipWs();
        return v;
    }

    static final class Parser {
        private final String s;
        private int i;
        private final Map<String, ExpressionValue> item;
        private final Map<String, String> names;
        private final Map<String, ExpressionValue> values;

        Parser(String s, Map<String, ExpressionValue> item, Map<String, String> names,
               Map<String, ExpressionValue> values) {
            this.s = s;
            this.item = item;
            this.names = names;
            this.values = values;
        }

        boolean parseOr() {
            boolean v = parseAnd();
            skipWs();
            while (matchWord("OR")) {
                boolean r = parseAnd();
                v = v || r;
                skipWs();
            }
            return v;
        }

        boolean parseAnd() {
            boolean v = parseNot();
            skipWs();
            while (matchWord("AND")) {
                boolean r = parseNot();
                v = v && r;
                skipWs();
            }
            return v;
        }

        boolean parseNot() {
            skipWs();
            if (matchWord("NOT")) {
                return !parsePrimary();
            }
            return parsePrimary();
        }

        boolean parsePrimary() {
            skipWs();
            if (peek() == '(') {
                i++;
                boolean v = parseOr();
                skipWs();
                if (peek() == ')') {
                    i++;
                }
                return v;
            }
            if (matchWord("attribute_not_exists")) {
                String attr = parseParenName();
                // DynamoDB: an explicit NULL attribute exists. Only a missing key is absent.
                return item.get(attr) == null;
            }
            if (matchWord("attribute_exists")) {
                String attr = parseParenName();
                return item.get(attr) != null;
            }
            if (matchWord("attribute_type")) {
                return parseAttributeType();
            }
            if (matchWord("begins_with")) {
                return parseBeginsOrContains(true);
            }
            if (matchWord("contains")) {
                return parseBeginsOrContains(false);
            }
            return parseComparison();
        }

        boolean parseAttributeType() {
            skipWs();
            expect('(');
            String attr = resolveName(parseToken());
            skipWs();
            expect(',');
            skipWs();
            ExpressionValue typeVal = resolveValue(parseToken());
            skipWs();
            expect(')');
            ExpressionValue lhs = item.get(attr);
            if (lhs == null || typeVal == null || typeVal.s() == null) {
                return false;
            }
            return dynamoTypeName(lhs).equalsIgnoreCase(typeVal.s());
        }

        static String dynamoTypeName(ExpressionValue v) {
            switch (v.kind()) {
                case S:
                    return "S";
                case N:
                    return "N";
                case B:
                    return "B";
                case BOOL:
                    return "BOOL";
                case NULL:
                    return "NULL";
                default:
                    return "";
            }
        }

        boolean parseBeginsOrContains(boolean begins) {
            skipWs();
            expect('(');
            String attr = resolveName(parseToken());
            skipWs();
            expect(',');
            skipWs();
            ExpressionValue rhs = resolveValue(parseToken());
            skipWs();
            expect(')');
            ExpressionValue lhs = item.get(attr);
            if (lhs == null || lhs.s() == null || rhs == null || rhs.s() == null) {
                return false;
            }
            return begins ? lhs.s().startsWith(rhs.s()) : lhs.s().contains(rhs.s());
        }

        boolean parseComparison() {
            String leftTok = parseToken();
            skipWs();
            if (matchWord("BETWEEN")) {
                String attr = resolveName(leftTok);
                ExpressionValue lo = resolveValue(parseToken());
                skipWs();
                matchWord("AND");
                ExpressionValue hi = resolveValue(parseToken());
                ExpressionValue lhs = item.get(attr);
                if (lhs == null || lhs.s() == null || lo == null || hi == null) {
                    return false;
                }
                return lhs.s().compareTo(lo.s()) >= 0 && lhs.s().compareTo(hi.s()) <= 0;
            }
            if (matchWord("IN")) {
                String attr = resolveName(leftTok);
                skipWs();
                expect('(');
                List<ExpressionValue> list = new ArrayList<ExpressionValue>();
                while (true) {
                    skipWs();
                    if (peek() == ')') {
                        i++;
                        break;
                    }
                    list.add(resolveValue(parseToken()));
                    skipWs();
                    if (peek() == ',') {
                        i++;
                    }
                }
                ExpressionValue lhs = item.get(attr);
                for (int k = 0; k < list.size(); k++) {
                    if (eq(lhs, list.get(k))) {
                        return true;
                    }
                }
                return false;
            }
            String op;
            if (s.startsWith("<>", i)) {
                op = "<>";
                i += 2;
            } else if (s.startsWith("<=", i)) {
                op = "<=";
                i += 2;
            } else if (s.startsWith(">=", i)) {
                op = ">=";
                i += 2;
            } else if (peek() == '<' || peek() == '>' || peek() == '=') {
                op = String.valueOf(s.charAt(i++));
            } else {
                return false;
            }
            skipWs();
            String rightTok = parseToken();
            String attr = resolveName(leftTok);
            ExpressionValue rhs = resolveValue(rightTok);
            ExpressionValue lhs = item.get(attr);
            return compare(lhs, rhs, op);
        }

        String parseParenName() {
            skipWs();
            expect('(');
            skipWs();
            String tok = parseToken();
            skipWs();
            expect(')');
            return resolveName(tok);
        }

        String resolveName(String tok) {
            if (tok.startsWith("#")) {
                String mapped = names.get(tok);
                return mapped == null ? tok.substring(1) : mapped;
            }
            return tok;
        }

        ExpressionValue resolveValue(String tok) {
            if (tok.startsWith(":")) {
                return values.get(tok);
            }
            return ExpressionValue.s(tok);
        }

        String parseToken() {
            skipWs();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '#' || s.charAt(i) == ':')) {
                i++;
            }
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_') {
                    i++;
                } else {
                    break;
                }
            }
            return s.substring(start, i);
        }

        boolean matchWord(String word) {
            skipWs();
            if (s.regionMatches(true, i, word, 0, word.length())) {
                int end = i + word.length();
                if (end == s.length() || !Character.isLetter(s.charAt(end))) {
                    i = end;
                    return true;
                }
            }
            return false;
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        void expect(char c) {
            skipWs();
            if (peek() == c) {
                i++;
            }
        }

        static boolean eq(ExpressionValue a, ExpressionValue b) {
            if (a == null || b == null) {
                return false;
            }
            if (a.kind() == ExpressionValue.Kind.BOOL || b.kind() == ExpressionValue.Kind.BOOL) {
                Boolean left = boolOf(a);
                Boolean right = boolOf(b);
                return left != null && left.equals(right);
            }
            if (a.kind() == ExpressionValue.Kind.N && b.kind() == ExpressionValue.Kind.N) {
                return new java.math.BigDecimal(a.n()).compareTo(new java.math.BigDecimal(b.n())) == 0;
            }
            if (a.kind() != b.kind()) {
                Object av = a.javaValue();
                Object bv = b.javaValue();
                return av != null && av.equals(bv);
            }
            return a.equals(b);
        }

        static Boolean boolOf(ExpressionValue v) {
            if (v == null) {
                return null;
            }
            if (v.kind() == ExpressionValue.Kind.BOOL) {
                return v.bool();
            }
            if (v.kind() == ExpressionValue.Kind.N) {
                return "0".equals(v.n()) ? Boolean.FALSE : Boolean.TRUE;
            }
            if (v.kind() == ExpressionValue.Kind.S) {
                return Boolean.valueOf("true".equalsIgnoreCase(v.s()) || "T".equals(v.s()));
            }
            return null;
        }

        static boolean compare(ExpressionValue lhs, ExpressionValue rhs, String op) {
            if ("=".equals(op)) {
                return eq(lhs, rhs);
            }
            if ("<>".equals(op)) {
                // DynamoDB: a missing attribute makes any comparison false. An
                // explicit NULL still exists and <> against a typed value is true
                // (type mismatch) — that is why the planner must AND with the
                // R-05 isNotNull shape instead of emitting bare <>.
                if (lhs == null || rhs == null) {
                    return false;
                }
                return !eq(lhs, rhs);
            }
            if (lhs == null || rhs == null) {
                return false;
            }
            int cmp;
            if (lhs.kind() == ExpressionValue.Kind.N && rhs.kind() == ExpressionValue.Kind.N) {
                cmp = new java.math.BigDecimal(lhs.n()).compareTo(new java.math.BigDecimal(rhs.n()));
            } else {
                String a = lhs.s() != null ? lhs.s() : String.valueOf(lhs.javaValue());
                String b = rhs.s() != null ? rhs.s() : String.valueOf(rhs.javaValue());
                cmp = a.compareTo(b);
            }
            if ("<".equals(op)) {
                return cmp < 0;
            }
            if ("<=".equals(op)) {
                return cmp <= 0;
            }
            if (">".equals(op)) {
                return cmp > 0;
            }
            if (">=".equals(op)) {
                return cmp >= 0;
            }
            return false;
        }
    }
}
