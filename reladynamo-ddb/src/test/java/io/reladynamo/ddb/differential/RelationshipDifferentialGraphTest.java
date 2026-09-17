package io.reladynamo.ddb.differential;

import com.gs.fw.common.mithra.finder.Operation;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.plan.ReladynamoResidualEvaluationException;
import io.reladynamo.core.plan.ReladynamoScanRequiredException;
import io.reladynamo.ddb.differential.domain.DiffBalance;
import io.reladynamo.ddb.differential.domain.DiffBalanceFinder;
import io.reladynamo.ddb.differential.domain.DiffBalanceList;
import io.reladynamo.ddb.differential.domain.DiffEntry;
import io.reladynamo.ddb.differential.domain.DiffEntryFinder;
import io.reladynamo.ddb.differential.domain.DiffEntryList;
import io.reladynamo.ddb.differential.domain.DiffTag;
import io.reladynamo.ddb.differential.domain.DiffTagFinder;
import io.reladynamo.ddb.differential.domain.DiffTagList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Relationship navigation compared differentially: the same finder call, on H2 and on DynamoDB,
 * with every attribute and all four temporal boundaries of every returned row compared, and the
 * parent→child association carried into the comparison rather than flattened away.
 *
 * <p>{@link RelationshipDifferentialTest} covers the one-level current-as-of case and its request
 * count. This class covers what that left open: the many-to-one direction, navigation at a past
 * business date and at a past processing date <em>after the children changed</em>, the two-level
 * chain, an empty relationship, a child set spread over many base-table partitions, and the two
 * shapes the adapter refuses — a relationship predicate inside a finder operation, and a
 * relationship whose foreign key has no GSI.
 *
 * <p>The refusal cases are not "expected failures". They assert the support contract: H2 answers
 * them, and DynamoDB declines <em>by name</em> instead of degrading to a Scan or silently dropping
 * rows. Weakening one into a pass would be a contract change, not a test repair.
 */
class RelationshipDifferentialGraphTest {

    private static final int CHILD_COUNT = RelationshipFixture.PARENTS.length
            * RelationshipFixture.CHILDREN_PER_PARENT;
    private static final int TAG_COUNT = CHILD_COUNT * RelationshipFixture.TAGS_PER_CHILD;

    private static RelationshipFixture fixture;

    @BeforeAll
    static void setUp() {
        fixture = RelationshipFixture.open();
    }

    @AfterAll
    static void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    // --- as-of navigation ---------------------------------------------------------

    @Test
    void navigation_at_a_past_business_date_agrees_between_stores() {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        fixture.bindH2();
        List<Map<String, Object>> reference = childGraph(RelationshipFixture.B0, inf);
        assertThat(reference)
                .as("at B0 every child is still alive and uncorrected, or the date comparison is vacuous")
                .hasSize(CHILD_COUNT);
        assertThat(RelationshipFixture.amountOf(reference, RelationshipFixture.UPDATED_ENTRY))
                .as("the B1 correction must not be visible at B0")
                .isNotEqualTo(Double.valueOf(RelationshipFixture.UPDATED_AMOUNT));

        fixture.bindDynamo();
        RelationshipFixture.assertGraphAgrees("navigation at businessDate=B0", reference,
                childGraph(RelationshipFixture.B0, inf));
    }

    @Test
    void navigation_at_the_current_business_date_sees_the_correction_and_the_termination() {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        fixture.bindH2();
        List<Map<String, Object>> reference = childGraph(RelationshipFixture.B1, inf);
        assertThat(reference)
                .as("one child was terminated effective B1, so the graph must be one smaller")
                .hasSize(CHILD_COUNT - 1);
        assertThat(RelationshipFixture.amountOf(reference, RelationshipFixture.UPDATED_ENTRY))
                .isEqualTo(Double.valueOf(RelationshipFixture.UPDATED_AMOUNT));
        assertThat(RelationshipFixture.childrenOfParent(reference, RelationshipFixture.TERMINATED_ENTRY_PARENT))
                .hasSize(RelationshipFixture.CHILDREN_PER_PARENT - 1);

        fixture.bindDynamo();
        RelationshipFixture.assertGraphAgrees("navigation at businessDate=B1", reference,
                childGraph(RelationshipFixture.B1, inf));
    }

    @Test
    void navigation_at_a_past_processing_date_sees_the_graph_before_the_change() {
        fixture.bindH2();
        List<Map<String, Object>> reference = childGraph(RelationshipFixture.B1, RelationshipFixture.P_MID);
        assertThat(reference)
                .as("at a processing date before the correcting transaction the terminated child is back")
                .hasSize(CHILD_COUNT);
        assertThat(RelationshipFixture.amountOf(reference, RelationshipFixture.UPDATED_ENTRY))
                .as("the correction was committed at P1 and must be invisible at P_MID")
                .isNotEqualTo(Double.valueOf(RelationshipFixture.UPDATED_AMOUNT));

        fixture.bindDynamo();
        RelationshipFixture.assertGraphAgrees("navigation at businessDate=B1, processingDate=P_MID", reference,
                childGraph(RelationshipFixture.B1, RelationshipFixture.P_MID));
    }

    // --- direction, cardinality, partitioning -------------------------------------

    @Test
    void many_to_one_navigation_agrees_between_stores() {
        Timestamp inf = DiffEntryFinder.processingDate().getInfinityDate();
        fixture.bindH2();
        List<Map<String, Object>> reference = parentsViaChildren(RelationshipFixture.B0, inf);
        assertThat(reference)
                .as("every live child must reach its parent, or the reverse direction is untested")
                .hasSize(CHILD_COUNT);

        fixture.bindDynamo();
        RelationshipFixture.assertGraphAgrees("many-to-one navigation entry.getBalance()", reference,
                parentsViaChildren(RelationshipFixture.B0, inf));
    }

    @Test
    void a_parent_with_no_children_navigates_to_an_empty_list_on_both_stores() {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        fixture.bindH2();
        List<Map<String, Object>> reference = childGraphOf(RelationshipFixture.LONELY_PARENT,
                RelationshipFixture.B0, inf);
        assertThat(reference).isEmpty();

        fixture.bindDynamo();
        // The parent itself must still resolve — an empty child list is the answer, not a refusal
        // and not a missing parent.
        DiffBalanceList parents = resolvedParents(
                oneParent(RelationshipFixture.LONELY_PARENT, RelationshipFixture.B0, inf), true);
        assertThat(parents.size()).isEqualTo(1);
        assertThat(parents.get(0).getEntries().size())
                .as("DynamoDB returned children for a parent that has none")
                .isEqualTo(0);
    }

    /**
     * Navigation with no {@code deepFetch}. A generated one-to-many accessor builds a
     * {@code RelationshipMultiEqualityOperation} for a single parent rather than the
     * {@code foreignKey IN (...)} a batched deep fetch builds, and the planner used to see that as
     * one opaque atom and refuse with PLAN-001 — a navigation H2 answers and the foreign-key GSI
     * can serve. This is the shape that caught it.
     */
    @Test
    void lazy_navigation_without_deep_fetch_agrees_and_uses_the_foreign_key_gsi() {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        int parentId = RelationshipFixture.PARENTS[0];

        fixture.bindH2();
        List<Map<String, Object>> reference = lazyChildrenOf(parentId, RelationshipFixture.B0, inf);
        assertThat(reference).hasSize(RelationshipFixture.CHILDREN_PER_PARENT);

        fixture.bindDynamo();
        fixture.counter().reset();
        List<Map<String, Object>> adapter = lazyChildrenOf(parentId, RelationshipFixture.B0, inf);
        RelationshipFixture.assertGraphAgrees("lazy parent.getEntries() without deepFetch", reference, adapter);
        assertThat(fixture.counter().describe())
                .as("the foreign-key GSI must serve a lazy navigation too, without a Scan")
                .contains("scan=0");
    }

    @Test
    void a_child_set_spanning_many_base_table_partitions_is_served_without_a_scan() {
        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        Set<String> partitions = new HashSet<String>();
        for (int id = RelationshipFixture.FIRST_ENTRY; id <= RelationshipFixture.LAST_ENTRY; id++) {
            partitions.add(keys.partitionKey(fixture.entryMapping(),
                    Collections.<String, Object>singletonMap("entryId", Integer.valueOf(id))));
        }
        assertThat(partitions)
                .as("each child is its own base-table partition; a single-partition child set would "
                        + "make the fan-out assertion meaningless")
                .hasSize(CHILD_COUNT);

        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        fixture.bindH2();
        List<Map<String, Object>> reference = childGraph(RelationshipFixture.B0, inf);

        fixture.bindDynamo();
        DiffBalanceList parents = resolvedParents(parentsAt(RelationshipFixture.B0, inf), true);
        fixture.counter().reset();
        parents.deepFetch(DiffBalanceFinder.entries());
        List<Map<String, Object>> adapter = childrenOf(parents);
        RelationshipFixture.assertGraphAgrees("deep fetch across " + CHILD_COUNT + " base partitions", reference, adapter);
        assertThat(fixture.counter().describe())
                .as("the foreign-key GSI must serve the whole child set: no Scan and no per-child GetItem")
                .contains("scan=0")
                .contains("getItem=0");
        assertThat(fixture.counter().reads())
                .as("%s children in %s partitions must not cost one request each (%s)",
                        Integer.valueOf(CHILD_COUNT), Integer.valueOf(partitions.size()),
                        fixture.counter().describe())
                .isLessThan(RelationshipFixture.PARENTS.length);
    }

    // --- two levels ---------------------------------------------------------------

    @Test
    void two_level_deep_fetch_agrees_between_stores() {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        fixture.bindH2();
        List<Map<String, Object>> reference = tagGraph(RelationshipFixture.B0, inf);
        assertThat(reference)
                .as("balance -> entries -> tags must reach every grandchild on H2")
                .hasSize(TAG_COUNT);

        fixture.bindDynamo();
        RelationshipFixture.assertGraphAgrees("two-level deep fetch balance -> entries -> tags", reference,
                tagGraph(RelationshipFixture.B0, inf));
    }

    @Test
    void two_level_deep_fetch_costs_one_request_per_level_not_one_per_row() {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        fixture.bindDynamo();
        DiffBalanceList parents = resolvedParents(parentsAt(RelationshipFixture.B0, inf), true);
        fixture.counter().reset();
        parents.deepFetch(DiffBalanceFinder.entries());
        parents.deepFetch(DiffBalanceFinder.entries().tags());

        int tags = 0;
        for (int i = 0; i < parents.size(); i++) {
            DiffEntryList entries = parents.get(i).getEntries();
            for (int j = 0; j < entries.size(); j++) {
                tags += entries.get(j).getTags().size();
            }
        }
        assertThat(tags)
                .as("a low request count with no grandchildren returned would be a false win")
                .isEqualTo(TAG_COUNT);
        assertThat(fixture.counter().describe())
                .as("neither level may Scan or fall back to per-row GetItem")
                .contains("scan=0")
                .contains("getItem=0");
        assertThat(fixture.counter().reads())
                .as("%s children plus %s grandchildren over two levels cost %s reads (%s); "
                        + "one request per level is the shape this asserts",
                        Integer.valueOf(CHILD_COUNT), Integer.valueOf(TAG_COUNT),
                        Integer.valueOf(fixture.counter().reads()), fixture.counter().describe())
                .isLessThanOrEqualTo(2);
    }

    // --- refusals -----------------------------------------------------------------

    @Test
    void a_relationship_attribute_predicate_is_refused_by_name_although_h2_answers_it() {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        Operation withPredicate = parentsAt(RelationshipFixture.B0, inf)
                .and(DiffBalanceFinder.entries().amount().greaterThan(11.0));

        fixture.bindH2();
        DiffBalanceList onH2 = resolvedParents(withPredicate, true);
        assertThat(onH2.size())
                .as("H2 must answer the mapped predicate, or the refusal below proves nothing")
                .isGreaterThan(0);

        fixture.bindDynamo();
        assertThatThrownBy(() -> resolvedParents(withPredicate, true).size())
                .as("a relationship predicate has no DynamoDB access path and must be refused by name")
                .satisfies(thrown -> {
                    ReladynamoResidualEvaluationException refusal = RelationshipFixture.causeOfType(
                            thrown, ReladynamoResidualEvaluationException.class);
                    assertThat(refusal)
                            .as("expected RELADYNAMO-RESIDUAL-001 somewhere in the cause chain of %s",
                                    thrown)
                            .isNotNull();
                    assertThat(refusal.getMessage()).startsWith("RELADYNAMO-RESIDUAL-001:");
                });
    }

    @Test
    void a_relationship_exists_predicate_is_refused_by_name_although_h2_answers_it() {
        assertMappedPredicateRefused(DiffBalanceFinder.entries().exists(),
                RelationshipFixture.PARENTS.length);
    }

    @Test
    void a_relationship_not_exists_predicate_is_refused_by_name_although_h2_answers_it() {
        assertMappedPredicateRefused(DiffBalanceFinder.entries().notExists(), 1);
    }

    @Test
    void deep_fetch_of_a_relationship_without_its_foreign_key_gsi_is_refused_by_name() {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        fixture.bindH2();
        assertThat(childGraph(RelationshipFixture.B0, inf))
                .as("H2 resolves the relationship, so the refusal is about the access path only")
                .hasSize(CHILD_COUNT);

        fixture.bindDynamoWithoutEntryGsi();
        assertThatThrownBy(() -> childGraph(RelationshipFixture.B0, inf))
                .as("a foreign-key navigation with no GSI on the foreign key must refuse, not Scan")
                .satisfies(thrown -> {
                    ReladynamoScanRequiredException refusal = RelationshipFixture.causeOfType(
                            thrown, ReladynamoScanRequiredException.class);
                    assertThat(refusal)
                            .as("expected RELADYNAMO-PLAN-001 somewhere in the cause chain of %s", thrown)
                            .isNotNull();
                    assertThat(refusal.getMessage()).startsWith("RELADYNAMO-PLAN-001:");
                });
    }

    private static void assertMappedPredicateRefused(Operation relationshipPredicate,
                                                     int expectedOnH2) {
        Timestamp inf = DiffBalanceFinder.processingDate().getInfinityDate();
        Operation op = allParentsAt(RelationshipFixture.B0, inf).and(relationshipPredicate);

        fixture.bindH2();
        assertThat(resolvedParents(op, true).size())
                .as("H2 must answer %s", relationshipPredicate)
                .isEqualTo(expectedOnH2);

        fixture.bindDynamo();
        assertThatThrownBy(() -> resolvedParents(op, true).size())
                .as("exists/notExists needs relationship resolution below the seam and must be refused")
                .satisfies(thrown -> assertThat(RelationshipFixture.causeOfType(
                        thrown, ReladynamoResidualEvaluationException.class))
                        .as("expected RELADYNAMO-RESIDUAL-001 in the cause chain of %s", thrown)
                        .isNotNull());
    }

    // --- navigation helpers -------------------------------------------------------

    private static List<Map<String, Object>> childGraph(Timestamp business, Timestamp processing) {
        return childrenOf(deepFetchedParents(parentsAt(business, processing)));
    }

    private static List<Map<String, Object>> childGraphOf(int parentId, Timestamp business,
                                                          Timestamp processing) {
        return childrenOf(deepFetchedParents(oneParent(parentId, business, processing)));
    }

    /** One parent, resolved on its own, then navigated with no deep fetch at all. */
    private static List<Map<String, Object>> lazyChildrenOf(int parentId, Timestamp business,
                                                            Timestamp processing) {
        DiffBalanceList parents = resolvedParents(oneParent(parentId, business, processing), true);
        assertThat(parents.size())
                .as("parent %s must resolve before it can be navigated", Integer.valueOf(parentId))
                .isEqualTo(1);
        DiffBalance parent = parents.get(0);
        return RelationshipFixture.rowsOf(DiffEntryFinder.getFinderInstance(),
                parent.getEntries(), parent.getBalanceId());
    }

    private static DiffBalanceList deepFetchedParents(Operation op) {
        DiffBalanceList parents = DiffBalanceFinder.findMany(op);
        parents.setBypassCache(true);
        parents.deepFetch(DiffBalanceFinder.entries());
        parents.forceResolve();
        return parents;
    }

    private static DiffBalanceList resolvedParents(Operation op, boolean bypassCache) {
        DiffBalanceList parents = DiffBalanceFinder.findMany(op);
        parents.setBypassCache(bypassCache);
        parents.forceResolve();
        return parents;
    }

    private static List<Map<String, Object>> childrenOf(DiffBalanceList parents) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < parents.size(); i++) {
            DiffBalance parent = parents.get(i);
            rows.addAll(RelationshipFixture.rowsOf(DiffEntryFinder.getFinderInstance(),
                    parent.getEntries(), parent.getBalanceId()));
        }
        return rows;
    }

    /** Children resolved by their own key, then navigated back to the parent they belong to. */
    private static List<Map<String, Object>> parentsViaChildren(Timestamp business,
                                                                Timestamp processing) {
        DiffEntryList children = DiffEntryFinder.findMany(childrenAt(business, processing));
        children.setBypassCache(true);
        children.deepFetch(DiffEntryFinder.balance());
        children.forceResolve();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < children.size(); i++) {
            DiffEntry child = children.get(i);
            DiffBalance parent = child.getBalance();
            assertThat(parent)
                    .as("child %s reached no parent", Integer.valueOf(child.getEntryId()))
                    .isNotNull();
            rows.add(RelationshipFixture.rowOf(DiffBalanceFinder.getFinderInstance(), parent,
                    child.getEntryId()));
        }
        return rows;
    }

    private static List<Map<String, Object>> tagGraph(Timestamp business, Timestamp processing) {
        DiffBalanceList parents = DiffBalanceFinder.findMany(parentsAt(business, processing));
        parents.setBypassCache(true);
        parents.deepFetch(DiffBalanceFinder.entries());
        parents.deepFetch(DiffBalanceFinder.entries().tags());
        parents.forceResolve();

        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < parents.size(); i++) {
            DiffBalance parent = parents.get(i);
            DiffEntryList entries = parent.getEntries();
            for (int j = 0; j < entries.size(); j++) {
                DiffEntry entry = entries.get(j);
                DiffTagList tags = entry.getTags();
                for (int k = 0; k < tags.size(); k++) {
                    DiffTag tag = tags.get(k);
                    Map<String, Object> row = MithraDataAccessor.extract(
                            DiffTagFinder.getFinderInstance(), tag.zGetCurrentData());
                    // Both hops participate in the comparison: a grandchild attached to the wrong
                    // child, or to the right child under the wrong parent, is a divergence.
                    row.put(RelationshipFixture.NAV_PARENT, Integer.valueOf(entry.getEntryId()));
                    row.put("navGrandParentId", Integer.valueOf(parent.getBalanceId()));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    // --- operations ---------------------------------------------------------------

    private static Operation parentsAt(Timestamp business, Timestamp processing) {
        return asOf(parentIdsOr(RelationshipFixture.PARENTS.length), business, processing);
    }

    /** The four parents that have children, plus the childless one. */
    private static Operation allParentsAt(Timestamp business, Timestamp processing) {
        return asOf(parentIdsOr(RelationshipFixture.PARENTS.length)
                .or(DiffBalanceFinder.balanceId().eq(RelationshipFixture.LONELY_PARENT)),
                business, processing);
    }

    private static Operation oneParent(int balanceId, Timestamp business, Timestamp processing) {
        return asOf(DiffBalanceFinder.balanceId().eq(balanceId), business, processing);
    }

    private static Operation parentIdsOr(int howMany) {
        // Equality OR, not a key range: the planner will not Query a range of partition keys.
        Operation ids = DiffBalanceFinder.balanceId().eq(RelationshipFixture.PARENTS[0]);
        for (int i = 1; i < howMany; i++) {
            ids = ids.or(DiffBalanceFinder.balanceId().eq(RelationshipFixture.PARENTS[i]));
        }
        return ids;
    }

    private static Operation asOf(Operation op, Timestamp business, Timestamp processing) {
        return op.and(DiffBalanceFinder.businessDate().eq(business))
                .and(DiffBalanceFinder.processingDate().eq(processing));
    }

    private static Operation childrenAt(Timestamp business, Timestamp processing) {
        Operation ids = DiffEntryFinder.entryId().eq(RelationshipFixture.FIRST_ENTRY);
        for (int id = RelationshipFixture.FIRST_ENTRY + 1; id <= RelationshipFixture.LAST_ENTRY; id++) {
            ids = ids.or(DiffEntryFinder.entryId().eq(id));
        }
        return ids.and(DiffEntryFinder.businessDate().eq(business))
                .and(DiffEntryFinder.processingDate().eq(processing));
    }
}
