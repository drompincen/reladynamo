package com.reladynamo.demo.classifier;

import com.gs.fw.common.mithra.MithraDatedTransactionalObject;
import com.gs.fw.common.mithra.MithraList;
import com.gs.fw.common.mithra.MithraTransactionalObject;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.util.DefaultInfinityTimestamp;
import com.reladynamo.demo.classifier.domain.Car;
import com.reladynamo.demo.classifier.domain.CarFinder;
import com.reladynamo.demo.classifier.domain.CarList;
import com.reladynamo.demo.classifier.domain.ClassificationRuleFinder;
import com.reladynamo.demo.classifier.domain.ClassificationRuleList;
import com.reladynamo.demo.classifier.domain.ResultLabelFinder;
import com.reladynamo.demo.classifier.domain.ResultLabelList;
import com.reladynamo.demo.classifier.domain.RuleCriterionFinder;
import com.reladynamo.demo.classifier.domain.RuleCriterionList;
import com.reladynamo.demo.classifier.util.Dates;
import com.reladynamo.demo.classifier.util.ReladomoRuntime;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The headline claim, on the smallest demo: the demo's own object-model XML is parsed
 * generically, H2 is seeded exactly as today, those rows are mirrored through
 * {@link DynamoDbWriter}, and classification from the DynamoDB rows equals classification
 * from H2. A 1985 Toyota MR2 must walk {@code COOL → EIGHTIES_COOL → RETRO} from DynamoDB.
 *
 * <p>Row sets are compared with {@link TemporalRowSetDiffer}. A real divergence is left
 * failing — it is not weakened to reach green.
 *
 * <p>This does not yet rebind Reladomo finders onto DynamoDB. Classification on the adapter
 * side is computed from decoded items with the same half-open as-of rule Reladomo uses
 * ({@code from &lt;= asOf &lt; thru}, current processing = {@code processingDateTo} is
 * infinity). Finder materialisation is a separate seam, already gated in the adapter module.
 */
class ClassifierDynamoDifferentialTest
{
    private static final Timestamp[] AS_OF_DATES = {
            Dates.AS_OF_2012,
            Dates.AS_OF_2016,
            Dates.AS_OF_2021,
            Dates.TODAY
    };

    private static LocalDynamoDb ddb;
    private static EntityMapping carMapping;
    private static EntityMapping ruleMapping;
    private static EntityMapping criterionMapping;
    private static EntityMapping labelMapping;
    private static EntityMapping resultMapping;
    private static Store cars;
    private static Store rules;
    private static Store criteria;
    private static Store labels;

    private Classifier classifier;
    private List<Map<String, Object>> ddbCars;
    private List<Map<String, Object>> ddbRules;
    private List<Map<String, Object>> ddbCriteria;

    @BeforeAll
    static void startStores()
    {
        ReladomoRuntime.start();
        ddb = LocalDynamoDb.start();

        // The mapping comes from the SAME XML the generated objects came from — that is what
        // makes this a test of the generic adapter rather than of a hand-written mapping.
        MithraObjectXmlParser parser = new MithraObjectXmlParser();
        carMapping = parser.parse(loadDemoXml("Car.xml"));
        ruleMapping = parser.parse(loadDemoXml("ClassificationRule.xml"));
        criterionMapping = parser.parse(loadDemoXml("RuleCriterion.xml"));
        labelMapping = parser.parse(loadDemoXml("ResultLabel.xml"));
        resultMapping = parser.parse(loadDemoXml("ClassificationResult.xml"));

        TableCreator creator = new TableCreator(ddb.client());
        creator.create(carMapping);
        creator.create(ruleMapping);
        creator.create(criterionMapping);
        creator.create(labelMapping);
        creator.create(resultMapping);

        cars = new Store(ddb, carMapping);
        rules = new Store(ddb, ruleMapping);
        criteria = new Store(ddb, criterionMapping);
        labels = new Store(ddb, labelMapping);
    }

    @AfterAll
    static void stopStores()
    {
        if (ddb != null)
        {
            ddb.close();
        }
    }

    @BeforeEach
    void seedH2AndMirrorIntoDynamo()
    {
        ReladomoRuntime.wipeData();
        DemoSeed.insertCanonical();
        classifier = new Classifier();

        List<Map<String, Object>> h2Cars = extractNonDated(CarFinder.getFinderInstance(), allCars());
        List<Map<String, Object>> h2Rules = extractDated(
                ClassificationRuleFinder.getFinderInstance(), allRuleVersions());
        List<Map<String, Object>> h2Criteria = extractDated(
                RuleCriterionFinder.getFinderInstance(), allCriterionVersions());
        List<Map<String, Object>> h2Labels = extractNonDated(
                ResultLabelFinder.getFinderInstance(), allLabels());

        cars.purgeAll(cars.scanAll());
        rules.purgeAll(rules.scanAll());
        criteria.purgeAll(criteria.scanAll());
        labels.purgeAll(labels.scanAll());

        cars.push(h2Cars);
        rules.push(h2Rules);
        criteria.push(h2Criteria);
        labels.push(h2Labels);

        ddbCars = cars.scanAll();
        ddbRules = rules.scanAll();
        ddbCriteria = criteria.scanAll();

        assertIdentical("CAR", h2Cars, ddbCars);
        assertIdentical("CLASSIFICATION_RULE", h2Rules, ddbRules);
        assertIdentical("RULE_CRITERION", h2Criteria, ddbCriteria);
        assertIdentical("RESULT_LABEL", h2Labels, labels.scanAll());
    }

    @Test
    void should_walk_mr2_cool_to_eighties_cool_to_retro_from_dynamodb()
    {
        Map<String, Object> mr2 = carRow(DemoSeed.MR2_ID);

        assertThat(classifyFromDynamo(mr2, Dates.AS_OF_2012)).isEqualTo(Labels.COOL);
        assertThat(classifyFromDynamo(mr2, Dates.AS_OF_2016)).isEqualTo(Labels.EIGHTIES_COOL);
        assertThat(classifyFromDynamo(mr2, Dates.AS_OF_2021)).isEqualTo(Labels.RETRO);
        assertThat(classifyFromDynamo(mr2, Dates.TODAY)).isEqualTo(Labels.RETRO);
    }

    @Test
    void should_classify_every_seeded_car_the_same_from_dynamodb_as_from_h2()
    {
        for (int carId = 1; carId <= 10; carId++)
        {
            Car h2Car = CarFinder.findOne(CarFinder.carId().eq(carId));
            Map<String, Object> ddbCar = carRow(carId);
            for (int d = 0; d < AS_OF_DATES.length; d++)
            {
                Timestamp asOf = AS_OF_DATES[d];
                String fromH2 = classifier.classify(h2Car, asOf).getResultLabel();
                String fromDdb = classifyFromDynamo(ddbCar, asOf);
                assertThat(fromDdb)
                        .as("car %s as of %s: DynamoDB classification must equal H2",
                                h2Car.displayName(), asOf)
                        .isEqualTo(fromH2);
            }
        }
    }

    private String classifyFromDynamo(Map<String, Object> car, Timestamp asOf)
    {
        Map<String, Object> winner = null;
        for (int i = 0; i < ddbRules.size(); i++)
        {
            Map<String, Object> rule = ddbRules.get(i);
            if (!visibleAtCurrentProcessing(rule, asOf))
            {
                continue;
            }
            if (!Boolean.TRUE.equals(rule.get("active")))
            {
                continue;
            }
            if (!ruleMatches(car, rule, asOf))
            {
                continue;
            }
            if (winner == null
                    || intValue(rule, "priority") > intValue(winner, "priority")
                    || (intValue(rule, "priority") == intValue(winner, "priority")
                    && intValue(rule, "ruleId") < intValue(winner, "ruleId")))
            {
                winner = rule;
            }
        }
        return winner == null ? Labels.UNCLASSIFIED : (String) winner.get("resultLabel");
    }

    private boolean ruleMatches(Map<String, Object> car, Map<String, Object> rule, Timestamp asOf)
    {
        int ruleId = intValue(rule, "ruleId");
        int matched = 0;
        for (int i = 0; i < ddbCriteria.size(); i++)
        {
            Map<String, Object> criterion = ddbCriteria.get(i);
            if (intValue(criterion, "ruleId") != ruleId)
            {
                continue;
            }
            if (!visibleAtCurrentProcessing(criterion, asOf))
            {
                continue;
            }
            matched++;
            if (!criterionMatches(car, criterion))
            {
                return false;
            }
        }
        return matched > 0;
    }

    /**
     * Same operators and attribute names as {@link Classifier#criterionMatches}. Kept here so
     * the H2 path still goes through the real call site while DynamoDB is scored from decoded
     * rows. A mismatch against {@link Classifier#classify} fails the test.
     */
    static boolean criterionMatches(Map<String, Object> car, Map<String, Object> criterion)
    {
        String operator = (String) criterion.get("operator");
        String attributeName = (String) criterion.get("attributeName");
        if (isNumericAttribute(attributeName))
        {
            int actual = numericValue(car, attributeName);
            if ("EQ".equals(operator))
            {
                return actual == intValue(criterion, "valueNumericLow");
            }
            if ("NE".equals(operator))
            {
                return actual != intValue(criterion, "valueNumericLow");
            }
            if ("LT".equals(operator))
            {
                return actual < intValue(criterion, "valueNumericLow");
            }
            if ("GT".equals(operator))
            {
                return actual > intValue(criterion, "valueNumericLow");
            }
            if ("BETWEEN".equals(operator))
            {
                return actual >= intValue(criterion, "valueNumericLow")
                        && actual <= intValue(criterion, "valueNumericHigh");
            }
            if ("IN".equals(operator))
            {
                return numericIn(actual, (String) criterion.get("valueText"));
            }
            throw new IllegalArgumentException("Unsupported numeric operator: " + operator);
        }

        String actual = textValue(car, attributeName);
        if ("EQ".equals(operator))
        {
            return actual.equals(criterion.get("valueText"));
        }
        if ("NE".equals(operator))
        {
            return !actual.equals(criterion.get("valueText"));
        }
        if ("IN".equals(operator))
        {
            return textIn(actual, (String) criterion.get("valueText"));
        }
        throw new IllegalArgumentException("Unsupported text operator: " + operator);
    }

    private static boolean isNumericAttribute(String attributeName)
    {
        return "year".equals(attributeName) || "wheelCount".equals(attributeName);
    }

    private static int numericValue(Map<String, Object> car, String attributeName)
    {
        return intValue(car, attributeName);
    }

    private static String textValue(Map<String, Object> car, String attributeName)
    {
        Object value = car.get(attributeName);
        if (value == null)
        {
            throw new IllegalArgumentException("Not a text car attribute: " + attributeName);
        }
        return value.toString();
    }

    private static boolean numericIn(int actual, String valueText)
    {
        String[] parts = valueText.split(",");
        for (int i = 0; i < parts.length; i++)
        {
            if (Integer.parseInt(parts[i].trim()) == actual)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean textIn(String actual, String valueText)
    {
        String[] parts = valueText.split(",");
        for (int i = 0; i < parts.length; i++)
        {
            if (actual.equals(parts[i].trim()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Reladomo as-of: {@code from &lt;= asOf &lt; thru} on business date, current processing
     * knowledge (open processing interval: {@code processingDateTo} is infinity).
     */
    static boolean visibleAtCurrentProcessing(Map<String, Object> row, Timestamp asOf)
    {
        Timestamp businessFrom = (Timestamp) row.get("businessDateFrom");
        Timestamp businessTo = (Timestamp) row.get("businessDateTo");
        Timestamp processingTo = (Timestamp) row.get("processingDateTo");
        if (businessFrom == null || businessTo == null || processingTo == null)
        {
            return false;
        }
        return !businessFrom.after(asOf)
                && asOf.before(businessTo)
                && processingTo.equals(DefaultInfinityTimestamp.getDefaultInfinity());
    }

    private Map<String, Object> carRow(int carId)
    {
        for (int i = 0; i < ddbCars.size(); i++)
        {
            Map<String, Object> row = ddbCars.get(i);
            if (intValue(row, "carId") == carId)
            {
                return row;
            }
        }
        throw new IllegalStateException("no DynamoDB car row for carId=" + carId);
    }

    private static int intValue(Map<String, Object> row, String name)
    {
        Object value = row.get(name);
        if (!(value instanceof Number))
        {
            throw new IllegalStateException(
                    "expected Number for '" + name + "' but was " + value);
        }
        return ((Number) value).intValue();
    }

    private static void assertIdentical(String table,
                                        List<Map<String, Object>> reference,
                                        List<Map<String, Object>> adapter)
    {
        assertThat(reference)
                .as("%s: the H2 reference must produce rows, otherwise the comparison is vacuous", table)
                .isNotEmpty();
        RowSetDiff diff = TemporalRowSetDiffer.compare(reference, adapter);
        assertThat(diff.isIdentical())
                .as("H2 and DynamoDB disagree for %s:%n%s%nH2:%n%s%nDDB:%n%s",
                        table, diff.describe(), describeRows(reference), describeRows(adapter))
                .isTrue();
    }

    private static String describeRows(List<Map<String, Object>> rows)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" row(s)\n");
        for (int i = 0; i < rows.size(); i++)
        {
            sb.append("  [").append(i).append("] ").append(rows.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static CarList allCars()
    {
        CarList list = CarFinder.findMany(CarFinder.all());
        list.setBypassCache(true);
        return list;
    }

    private static ResultLabelList allLabels()
    {
        ResultLabelList list = ResultLabelFinder.findMany(ResultLabelFinder.all());
        list.setBypassCache(true);
        return list;
    }

    private static ClassificationRuleList allRuleVersions()
    {
        ClassificationRuleList list = ClassificationRuleFinder.findMany(
                ClassificationRuleFinder.businessDate().equalsEdgePoint()
                        .and(ClassificationRuleFinder.processingDate().equalsEdgePoint()));
        list.setBypassCache(true);
        return list;
    }

    private static RuleCriterionList allCriterionVersions()
    {
        RuleCriterionList list = RuleCriterionFinder.findMany(
                RuleCriterionFinder.businessDate().equalsEdgePoint()
                        .and(RuleCriterionFinder.processingDate().equalsEdgePoint()));
        list.setBypassCache(true);
        return list;
    }

    private static List<Map<String, Object>> extractDated(RelatedFinder finder, MithraList list)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); i++)
        {
            MithraDatedTransactionalObject item = (MithraDatedTransactionalObject) list.get(i);
            rows.add(MithraDataAccessor.extract(finder, item.zGetCurrentData()));
        }
        return rows;
    }

    private static List<Map<String, Object>> extractNonDated(RelatedFinder finder, MithraList list)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); i++)
        {
            MithraTransactionalObject item = (MithraTransactionalObject) list.get(i);
            rows.add(MithraDataAccessor.extract(finder, item.zGetCurrentData()));
        }
        return rows;
    }

    static String loadDemoXml(String fileName)
    {
        String path = "/reladomo/models/" + fileName;
        InputStream in = ClassifierDynamoDifferentialTest.class.getResourceAsStream(path);
        if (in == null)
        {
            throw new IllegalStateException("missing classpath resource " + path
                    + " — parse the demo's own XML, do not substitute a hand-written mapping");
        }
        StringBuilder xml = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                xml.append(line).append('\n');
            }
        }
        catch (Exception e)
        {
            throw new IllegalStateException("could not read " + path, e);
        }
        return xml.toString();
    }

    /** One mapped DynamoDB table plus the codec/writer used to push H2 snapshots through the adapter. */
    static final class Store
    {
        private final LocalDynamoDb ddb;
        private final EntityMapping mapping;
        private final ItemCodec codec;
        private final DynamoDbWriter writer;

        Store(LocalDynamoDb ddb, EntityMapping mapping)
        {
            this.ddb = ddb;
            this.mapping = mapping;
            this.codec = new ItemCodec(mapping);
            this.writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        }

        void push(List<Map<String, Object>> rows)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                // Snapshot replay, not an ORM insert: the same H2 version may already occupy
                // this pk+sk from an earlier assertion or a bound-portal write.
                writer.upsert(rows.get(i));
            }
        }

        void purgeAll(List<Map<String, Object>> rows)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                writer.purge(rows.get(i));
            }
        }

        List<Map<String, Object>> scanAll()
        {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            Map<String, AttributeValue> start = null;
            do
            {
                final Map<String, AttributeValue> exclusiveStart = start;
                ScanResponse response = ddb.client().scan(b ->
                {
                    b.tableName(mapping.tableName()).consistentRead(true);
                    if (exclusiveStart != null && !exclusiveStart.isEmpty())
                    {
                        b.exclusiveStartKey(exclusiveStart);
                    }
                });
                List<Map<String, AttributeValue>> items = response.items();
                for (int i = 0; i < items.size(); i++)
                {
                    rows.add(codec.decode(items.get(i)));
                }
                start = response.lastEvaluatedKey();
            }
            while (start != null && !start.isEmpty());
            return rows;
        }
    }
}
