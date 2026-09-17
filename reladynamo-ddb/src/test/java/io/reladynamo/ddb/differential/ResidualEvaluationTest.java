package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.AnalyzedOperation;
import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlan;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.core.plan.ReladynamoResidualEvaluationException;
import io.reladynamo.core.plan.ReladynamoUnplannableOperationException;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffEntryFinder;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-07: residual {@code Operation.matches} must see a Reladomo data/domain object, not the decoded
 * {@code Map}. Generated attributes cast to domain/data types; a map is a ClassCastException
 * or a silent wrong answer. These cases go through the real {@link QueryPlanExecutor} against
 * codec-written rows, with generated finder operations the planner actually leaves as residual.
 */
class ResidualEvaluationTest {

    private static LocalDynamoDb ddb;
    private static EntityMapping balanceMapping;
    private static EntityMapping entryMapping;
    private static DynamoDbWriter balanceWriter;
    private static DynamoDbWriter entryWriter;
    private static QueryPlanner planner;
    private static PhysicalDesign balanceDesign;
    private static QueryPlanExecutor executor;

    @BeforeAll
    static void setUp() {
        DifferentialSupport.boot();
        ddb = LocalDynamoDb.start();

        balanceMapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffBalance.xml"));
        entryMapping = new MithraObjectXmlParser().parse(
                DifferentialSupport.loadResource("/reladomo/models/DiffEntry.xml"));
        ItemCodec balanceCodec = new ItemCodec(balanceMapping);
        ItemCodec entryCodec = new ItemCodec(entryMapping);
        DifferentialSupport.createPkSkTable(ddb, balanceMapping.tableName());
        DifferentialSupport.createPkSkTable(ddb, entryMapping.tableName());

        balanceWriter = new DynamoDbWriter(ddb.client(), balanceMapping, balanceCodec, new DefaultKeyStrategy());
        entryWriter = new DynamoDbWriter(ddb.client(), entryMapping, entryCodec, new DefaultKeyStrategy());
        planner = new QueryPlanner();
        balanceDesign = PhysicalDesign.builder(balanceMapping)
                .infinityFrom(DiffBalanceFinder.getFinderInstance())
                .build();
        executor = new QueryPlanExecutor(ddb.client(), balanceCodec);
    }

    @AfterAll
    static void tearDown() {
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void should_filter_codec_written_rows_by_generated_endsWith_residual() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 12, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int keepId = 9411;
        int dropId = 9412;
        balanceWriter.insert(balanceRow(keepId, 1.0d, "alpha-TAIL", from, thru, inZ));
        balanceWriter.insert(balanceRow(dropId, 2.0d, "alpha-NOPE", from, thru, inZ));

        Operation op = twoIds(keepId, dropId, from, inZ)
                .and(DiffBalanceFinder.label().endsWith("-TAIL"));
        QueryPlan plan = planRequiringResidual(op, "end");

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(labelsOf(rows))
                .as("endsWith residual must keep the matching codec-written row and drop the miss")
                .containsExactly("alpha-TAIL");
    }

    @Test
    void should_filter_codec_written_rows_by_complex_like_residual() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 12, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int keepId = 9421;
        int dropId = 9422;
        // Reladomo wildCard: '?' is one character, '*' is any sequence. 'x?z*' matches "xyz-keep"
        // and rejects "xz-drop" (no middle character). Internal '?' is not a DynamoDB FilterExpression.
        balanceWriter.insert(balanceRow(keepId, 1.0d, "xyz-keep", from, thru, inZ));
        balanceWriter.insert(balanceRow(dropId, 2.0d, "xz-drop", from, thru, inZ));

        Operation op = twoIds(keepId, dropId, from, inZ)
                .and(DiffBalanceFinder.label().wildCardEq("x?z*"));
        QueryPlan plan = planRequiringResidual(op, "wild");

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(labelsOf(rows))
                .as("complex LIKE residual must evaluate against the typed label, not a Map")
                .containsExactly("xyz-keep");
    }

    @Test
    void should_filter_codec_written_rows_by_large_in_residual() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 12, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int keepId = 9431;
        int dropId = 9432;
        balanceWriter.insert(balanceRow(keepId, 1.0d, "keep-IN", from, thru, inZ));
        balanceWriter.insert(balanceRow(dropId, 2.0d, "not-in-set", from, thru, inZ));

        Set<String> labels = new HashSet<String>();
        for (int i = 0; i < PlannerConfig.DYNAMO_FILTER_IN_CAP; i++) {
            labels.add("in-val-" + i);
        }
        labels.add("keep-IN");
        assertThat(labels.size())
                .as("IN must exceed the DynamoDB FilterExpression cap so the planner leaves it residual")
                .isGreaterThan(PlannerConfig.DYNAMO_FILTER_IN_CAP);

        Operation op = twoIds(keepId, dropId, from, inZ)
                .and(DiffBalanceFinder.label().in(labels));
        QueryPlan plan = planRequiringResidual(op, "in");

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(labelsOf(rows))
                .as("large IN residual must keep the in-set label and drop the miss")
                .containsExactly("keep-IN");
    }

    @Test
    void should_refuse_or_answer_relationship_residual_when_cache_is_cold() {
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 12, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int parentId = 9441;
        int childId = 9541;
        balanceWriter.insert(balanceRow(parentId, 9.0d, "rel-parent", from, thru, inZ));
        entryWriter.insert(entryRow(childId, parentId, 3.0d, "rel-child", from, thru, inZ));

        DiffBalanceFinder.getMithraObjectPortal().getCache().clear();
        DiffEntryFinder.getMithraObjectPortal().getCache().clear();

        Operation op = currentAsOf(parentId)
                .and(DiffBalanceFinder.entries().label().eq("rel-child"));
        QueryPlan plan = planRequiringResidual(op, "entries");

        try {
            List<Map<String, Object>> rows = executor.execute(plan);
            assertThat(idsOf(rows))
                    .as("if the relationship residual is evaluated, the parent with a matching child "
                            + "must come back — an empty list is the silent wrong answer")
                    .containsExactly(Integer.valueOf(parentId));
        } catch (ReladynamoResidualEvaluationException e) {
            assertThat(e.getMessage())
                    .as("refusal must carry a stable code, not a bare ClassCastException")
                    .contains("RELADYNAMO-RESIDUAL-001");
        } catch (ReladynamoUnplannableOperationException e) {
            assertThat(e.getMessage())
                    .as("refusal must carry a stable code")
                    .contains("RELADYNAMO-");
        }
    }

    @Test
    void should_not_unbox_null_matches_from_a_generated_residual() {
        // Nullable contract: Operation.matches returns Boolean; null is not a pass and must not NPE.
        // A miss on endsWith is Boolean.FALSE, which is also not a pass — the row is dropped.
        Timestamp from = utc(2026, 1, 1);
        Timestamp thru = utc(2026, 12, 1);
        Timestamp inZ = utc(2026, 1, 2);
        int id = 9451;
        balanceWriter.insert(balanceRow(id, 1.0d, "nope", from, thru, inZ));

        Operation op = currentAsOf(id).and(DiffBalanceFinder.label().endsWith("-TAIL"));
        QueryPlan plan = planRequiringResidual(op, "end");

        List<Map<String, Object>> rows = executor.execute(plan);

        assertThat(rows)
                .as("a residual miss is not a pass; null/false must not be unboxed into an NPE")
                .isEmpty();
    }

    private static QueryPlan planRequiringResidual(Operation op, String residualHint) {
        QueryPlan plan = planner.plan(new AnalyzedOperation(op), balanceDesign);
        String dump = residualDump(plan).toLowerCase();
        assertThat(dump)
                .as("this regression is vacuous unless the planner left a residual: %s",
                        plan.toAssertableString())
                .isNotBlank();
        assertThat(dump)
                .as("residual dump must mention %s: %s", residualHint, plan.toAssertableString())
                .contains(residualHint);
        return plan;
    }

    /**
     * Fan-out parents keep an empty residual; the leftover predicate lives on each child.
     * Walking the tree is required or OR-of-PK cases look like they have no residual.
     */
    private static String residualDump(QueryPlan plan) {
        StringBuilder sb = new StringBuilder();
        appendResidualDump(plan, sb);
        return sb.toString();
    }

    private static void appendResidualDump(QueryPlan plan, StringBuilder sb) {
        if (plan.residual() != null && !plan.residual().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(plan.residualOperationDump());
        }
        List<QueryPlan> kids = plan.fanOut();
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                appendResidualDump(kids.get(i), sb);
            }
        }
    }

    private static Operation twoIds(int a, int b, Timestamp from, Timestamp inZ) {
        return currentAsOf(a).or(currentAsOf(b));
    }

    private static Operation currentAsOf(int id) {
        return DiffBalanceFinder.balanceId().eq(id)
                .and(DiffBalanceFinder.businessDate().eq(utc(2026, 6, 1)))
                .and(DiffBalanceFinder.processingDate().eq(
                        DiffBalanceFinder.processingDate().getInfinityDate()));
    }

    private static List<String> labelsOf(List<Map<String, Object>> rows) {
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++) {
            labels.add((String) rows.get(i).get("label"));
        }
        return labels;
    }

    private static List<Integer> idsOf(List<Map<String, Object>> rows) {
        List<Integer> ids = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            ids.add((Integer) rows.get(i).get("balanceId"));
        }
        return ids;
    }

    private static Map<String, Object> balanceRow(int id, double qty, String label,
                                                  Timestamp from, Timestamp thru, Timestamp inZ) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("balanceId", Integer.valueOf(id));
        row.put("quantity", Double.valueOf(qty));
        row.put("label", label);
        row.put("note", null);
        row.put("businessDateFrom", from);
        row.put("businessDateTo", thru);
        row.put("processingDateFrom", inZ);
        row.put("processingDateTo", DiffBalanceFinder.processingDate().getInfinityDate());
        return row;
    }

    private static Map<String, Object> entryRow(int entryId, int balanceId, double amount, String label,
                                                Timestamp from, Timestamp thru, Timestamp inZ) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("entryId", Integer.valueOf(entryId));
        row.put("balanceId", Integer.valueOf(balanceId));
        row.put("amount", Double.valueOf(amount));
        row.put("label", label);
        row.put("businessDateFrom", from);
        row.put("businessDateTo", thru);
        row.put("processingDateFrom", inZ);
        row.put("processingDateTo", DiffEntryFinder.processingDate().getInfinityDate());
        return row;
    }

    private static Timestamp utc(int y, int mo, int d) {
        return DifferentialSupport.utc(y, mo, d);
    }
}
