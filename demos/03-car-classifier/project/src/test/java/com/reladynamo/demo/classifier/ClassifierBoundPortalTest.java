package com.reladynamo.demo.classifier;

import com.gs.fw.common.mithra.MithraDatedTransactionalObject;
import com.gs.fw.common.mithra.MithraList;
import com.gs.fw.common.mithra.MithraTransactionalObject;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import com.gs.fw.common.mithra.util.DefaultInfinityTimestamp;
import com.reladynamo.demo.classifier.domain.Car;
import com.reladynamo.demo.classifier.domain.CarFinder;
import com.reladynamo.demo.classifier.domain.CarList;
import com.reladynamo.demo.classifier.domain.ClassificationResult;
import com.reladynamo.demo.classifier.domain.ClassificationResultFinder;
import com.reladynamo.demo.classifier.domain.ClassificationResultList;
import com.reladynamo.demo.classifier.domain.ClassificationRule;
import com.reladynamo.demo.classifier.domain.ClassificationRuleFinder;
import com.reladynamo.demo.classifier.domain.ClassificationRuleList;
import com.reladynamo.demo.classifier.domain.ResultLabelFinder;
import com.reladynamo.demo.classifier.domain.ResultLabelList;
import com.reladynamo.demo.classifier.domain.RuleCriterionFinder;
import com.reladynamo.demo.classifier.domain.RuleCriterionList;
import com.reladynamo.demo.classifier.util.Dates;
import com.reladynamo.demo.classifier.util.ReladomoRuntime;
import com.reladynamo.demo.classifier.util.Transactions;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.GsiSpec;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gs.fw.common.mithra.transaction.MithraDatedObjectPersister;
import com.reladynamo.demo.classifier.util.H2ConnectionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Inspection acceptance case 12 on the smallest real model: the classifier's existing
 * business operations run independently against H2 and against portals rebound with
 * {@code MithraAbstractObjectPortal.setMithraObjectReader}. Histories (all four
 * temporal boundaries), results and exceptions must agree exactly. A refusal is
 * asserted by its {@code RELADYNAMO-...} name; it is never skipped.
 *
 * <p>Call sites in {@link DemoSeed} and {@link Classifier} are unchanged. The processing
 * clock is pinned with {@code MithraTransaction.setProcessingStartTime} so IN_Z/OUT_Z
 * are comparable — see {@link Transactions#at}.
 */
class ClassifierBoundPortalTest
{
    private static final Timestamp[] AS_OF_DATES = {
            Dates.AS_OF_2012,
            Dates.AS_OF_2016,
            Dates.AS_OF_2021,
            Dates.TODAY
    };

    private static LocalDynamoDb ddb;
    private static BoundEntity cars;
    private static BoundEntity labels;
    private static BoundEntity rules;
    private static BoundEntity criteria;
    private static BoundEntity results;

    @BeforeAll
    static void startStores()
    {
        ReladomoRuntime.start();
        ddb = LocalDynamoDb.start();

        MithraObjectXmlParser parser = new MithraObjectXmlParser();
        EntityMapping carMapping = parser.parse(loadDemoXml("Car.xml"));
        EntityMapping labelMapping = parser.parse(loadDemoXml("ResultLabel.xml"));
        EntityMapping ruleMapping = parser.parse(loadDemoXml("ClassificationRule.xml"));
        EntityMapping criterionMapping = parser.parse(loadDemoXml("RuleCriterion.xml"));
        EntityMapping resultMapping = parser.parse(loadDemoXml("ClassificationResult.xml"));

        GsiSpec activeGsi = new GsiSpec(
                "gsi_active",
                Collections.singletonList("active"),
                GsiSpec.SK_BASE_TEMPORAL,
                false,
                GsiSpec.Projection.ALL,
                null);
        GsiSpec ruleIdGsi = GsiSpec.foreignKey("gsi_ruleId", "ruleId");

        TableCreator creator = new TableCreator(ddb.client());
        cars = BoundEntity.open(ddb, carMapping, CarFinder.getFinderInstance(),
                Collections.<GsiSpec>emptyList(), false);
        labels = BoundEntity.open(ddb, labelMapping, ResultLabelFinder.getFinderInstance(),
                Collections.<GsiSpec>emptyList(), false);
        rules = BoundEntity.open(ddb, ruleMapping, ClassificationRuleFinder.getFinderInstance(),
                Collections.singletonList(activeGsi), false);
        criteria = BoundEntity.open(ddb, criterionMapping, RuleCriterionFinder.getFinderInstance(),
                Collections.singletonList(ruleIdGsi), false);
        results = BoundEntity.open(ddb, resultMapping, ClassificationResultFinder.getFinderInstance(),
                Collections.<GsiSpec>emptyList(), false);

        creator.create(cars.design);
        creator.create(labels.design);
        creator.create(rules.design);
        creator.create(criteria.design);
        creator.create(results.design);
    }

    @AfterAll
    static void stopStores()
    {
        H2ConnectionManager.getInstance().reconnectRelationalSource();
        unbindPortals();
        if (ddb != null)
        {
            ddb.close();
        }
    }

    @Test
    void should_agree_with_h2_when_the_same_business_script_runs_independently_on_bound_portals()
    {
        ReladomoRuntime.wipeData();
        List<String> fromH2 = runBusinessScript();
        List<Map<String, Object>> h2Cars = extractNonDated(CarFinder.getFinderInstance(), allCars());
        List<Map<String, Object>> h2Labels = extractNonDated(ResultLabelFinder.getFinderInstance(), allLabels());
        List<Map<String, Object>> h2Rules = extractDated(
                ClassificationRuleFinder.getFinderInstance(), allRuleVersions());
        List<Map<String, Object>> h2Criteria = extractDated(
                RuleCriterionFinder.getFinderInstance(), allCriterionVersions());
        List<Map<String, Object>> h2Results = extractDated(
                ClassificationResultFinder.getFinderInstance(), allResultVersions());

        ReladomoRuntime.wipeData();
        purgeDynamo();
        bindPortals();
        List<String> fromDdb;
        try
        {
            fromDdb = runBoundScript();
        }
        finally
        {
            unbindPortals();
        }

        assertThat(fromH2)
                .as("the H2 reference must produce business results, otherwise the comparison is vacuous")
                .isNotEmpty();
        assertThat(fromDdb)
                .as("bound-portal results must equal the H2 run of the same script%nH2 : %s%nDDB: %s",
                        fromH2, fromDdb)
                .isEqualTo(fromH2);

        assertIdentical("CAR", cars.mapping, h2Cars, cars.scanAll());
        assertIdentical("RESULT_LABEL", labels.mapping, h2Labels, labels.scanAll());
        assertIdentical("CLASSIFICATION_RULE", rules.mapping, h2Rules, rules.scanAll());
        assertIdentical("RULE_CRITERION", criteria.mapping, h2Criteria, criteria.scanAll());
        assertIdentical("CLASSIFICATION_RESULT", results.mapping, h2Results, results.scanAll());
    }

    /**
     * Case 10: the same business script, H2 genuinely disconnected, Reladomo caches cleared.
     * Every SPI method actually reached must work (or refuse by name — which fails this test
     * as a finding). The method set is recorded, not inferred from the refusal list.
     */
    @Test
    void should_run_full_demo_surface_with_h2_disconnected_and_record_required_spi()
    {
        ReladomoRuntime.wipeData();
        purgeDynamo();
        cars.portal.getCache().clear();
        labels.portal.getCache().clear();
        rules.portal.getCache().clear();
        criteria.portal.getCache().clear();
        results.portal.getCache().clear();

        SpiRecorder carsRec = new SpiRecorder(cars.adapter);
        SpiRecorder labelsRec = new SpiRecorder(labels.adapter);
        SpiRecorder rulesRec = new SpiRecorder(rules.adapter);
        SpiRecorder criteriaRec = new SpiRecorder(criteria.adapter);
        SpiRecorder resultsRec = new SpiRecorder(results.adapter);
        cars.portal.setMithraObjectReader(carsRec.proxy());
        labels.portal.setMithraObjectReader(labelsRec.proxy());
        rules.portal.setMithraObjectReader(rulesRec.proxy());
        criteria.portal.setMithraObjectReader(criteriaRec.proxy());
        results.portal.setMithraObjectReader(resultsRec.proxy());
        H2ConnectionManager.getInstance().disconnectRelationalSource();
        List<String> fromDdb;
        try
        {
            fromDdb = runBoundScript();
        }
        finally
        {
            H2ConnectionManager.getInstance().reconnectRelationalSource();
            unbindPortals();
        }

        assertThat(fromDdb)
                .as("bound script with H2 disconnected must produce the demo's business results")
                .isNotEmpty();

        Set<String> reached = new LinkedHashSet<String>();
        reached.addAll(carsRec.methodsReached());
        reached.addAll(labelsRec.methodsReached());
        reached.addAll(rulesRec.methodsReached());
        reached.addAll(criteriaRec.methodsReached());
        reached.addAll(resultsRec.methodsReached());
        System.out.println("CASE-10-CLASSIFIER-REQUIRED-SPI=" + reached);
        assertThat(reached)
                .as("the classifier must actually call the adapter")
                .isNotEmpty();
        assertThat(reached).contains("insert", "find");

        Set<String> implemented = new LinkedHashSet<String>(Arrays.asList(
                "insert", "delete", "purge",
                "batchInsert", "batchDelete", "batchDeleteQuietly", "batchPurge",
                "update", "find", "count",
                "refresh", "refreshDatedObject", "enrollDatedObject",
                "setTxParticipationMode"));
        Set<String> refused = new LinkedHashSet<String>(reached);
        refused.removeAll(implemented);
        assertThat(refused)
                .as("every SPI method the classifier reached must work; a named refusal here is the next finding. reached=%s",
                        reached)
                .isEmpty();
    }

    /**
     * The demo's own operations, unchanged. Processing time is pinned so the two independent
     * runs share IN_Z / OUT_Z / evaluatedTime.
     */
    private static List<String> runBusinessScript()
    {
        DemoSeed.insertCanonical();
        Classifier classifier = new Classifier();
        List<String> out = new ArrayList<String>();

        Transactions.at(Dates.TODAY, new Runnable()
        {
            @Override
            public void run()
            {
                for (int carId = 1; carId <= 10; carId++)
                {
                    Car car = CarFinder.findOne(CarFinder.carId().eq(carId));
                    for (int d = 0; d < AS_OF_DATES.length; d++)
                    {
                        Classification c = classifier.classify(car, AS_OF_DATES[d]);
                        out.add(formatClassification("grid", c));
                    }
                }
            }
        });

        DemoSeed.insertWrongNinetiesRule();
        Car miata = CarFinder.findOne(CarFinder.carId().eq(DemoSeed.MIATA_ID));
        final Classification[] saidThen = new Classification[1];
        Transactions.at(Dates.WRONG_RULE_EVALUATED, new Runnable()
        {
            @Override
            public void run()
            {
                saidThen[0] = classifier.classify(miata, Dates.AS_OF_2012);
            }
        });
        out.add(formatClassification("saidThen", saidThen[0]));

        DemoSeed.correctWrongNinetiesRuleToEconomy();

        ClassificationResult audit = ClassificationResultFinder.findOne(
                ClassificationResultFinder.resultId().eq(saidThen[0].getResultId())
                        .and(ClassificationResultFinder.processingDate().eq(
                                DefaultInfinityTimestamp.getDefaultInfinity())));
        out.add("audit=" + audit.getResultLabel() + "/" + audit.getResultId());

        final Classification[] nowThink = new Classification[1];
        Transactions.at(Dates.AFTER_CORRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                nowThink[0] = classifier.classify(miata, Dates.AS_OF_2012);
            }
        });
        out.add(formatClassification("nowThink", nowThink[0]));

        ClassificationRule thenRule = ClassificationRuleFinder.findByPrimaryKey(
                DemoSeed.R8_WRONG, Dates.AS_OF_2012, Dates.WRONG_RULE_EVALUATED);
        ClassificationRule nowRule = ClassificationRuleFinder.findByPrimaryKey(
                DemoSeed.R8_WRONG, Dates.AS_OF_2012, DefaultInfinityTimestamp.getDefaultInfinity());
        out.add("thenRule=" + thenRule.getResultLabel());
        out.add("nowRule=" + nowRule.getResultLabel());

        Car sedan = CarFinder.findOne(CarFinder.carId().eq(DemoSeed.COROLLA_ID));
        Transactions.at(Dates.TERMINATE_PROCESSING, new Runnable()
        {
            @Override
            public void run()
            {
                out.add("beforeTerminate=" + classifier.classify(sedan, Dates.AS_OF_2013).getResultLabel());
            }
        });
        DemoSeed.terminateR1AsOf2014();
        Transactions.at(Dates.TERMINATE_PROCESSING, new Runnable()
        {
            @Override
            public void run()
            {
                out.add("afterTerminate2013=" + classifier.classify(sedan, Dates.AS_OF_2013).getResultLabel());
                out.add("afterTerminate2014=" + classifier.classify(sedan, Dates.AS_OF_2014).getResultLabel());
            }
        });
        return out;
    }

    private static List<String> runBoundScript()
    {
        try
        {
            return runBusinessScript();
        }
        catch (RuntimeException e)
        {
            String msg = rootCauseMessage(e);
            assertThat(msg)
                    .as("an operation that cannot be served must name the refusal "
                            + "(RELADYNAMO-... or the unimplemented SPI method), never fail opaquely. Actual: %s",
                            msg)
                    .containsAnyOf("RELADYNAMO-", "not implemented yet", "refresh",
                            "refreshDatedObject", "enrollDatedObject", "getForDateRange");
            fail("bound business operations could not be served: " + msg);
            return Collections.emptyList();
        }
    }

    /**
     * One {@code setMithraObjectReader} call per portal binds both reader and persister.
     * Verified on Reladomo 18.1.0 via javap and {@code BoundWritePathTest}.
     */
    private static void bindPortals()
    {
        cars.bind();
        labels.bind();
        rules.bind();
        criteria.bind();
        results.bind();
    }

    private static void unbindPortals()
    {
        if (cars != null)
        {
            cars.unbind();
        }
        if (labels != null)
        {
            labels.unbind();
        }
        if (rules != null)
        {
            rules.unbind();
        }
        if (criteria != null)
        {
            criteria.unbind();
        }
        if (results != null)
        {
            results.unbind();
        }
    }

    private static void purgeDynamo()
    {
        cars.purgeAll(cars.scanAll());
        labels.purgeAll(labels.scanAll());
        rules.purgeAll(rules.scanAll());
        criteria.purgeAll(criteria.scanAll());
        results.purgeAll(results.scanAll());
    }

    private static String formatClassification(String tag, Classification c)
    {
        return tag
                + " car=" + c.getCarId()
                + " asOf=" + c.getAsOfDate()
                + " label=" + c.getResultLabel()
                + " ruleId=" + c.getRuleId()
                + " ruleName=" + c.getRuleName()
                + " resultId=" + c.getResultId();
    }

    private static String rootCauseMessage(Throwable t)
    {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c)
        {
            c = c.getCause();
        }
        return String.valueOf(c.getMessage());
    }

    private static void assertIdentical(String table,
                                        EntityMapping mapping,
                                        List<Map<String, Object>> reference,
                                        List<Map<String, Object>> adapter)
    {
        assertThat(reference)
                .as("%s: the H2 reference must produce rows, otherwise the comparison is vacuous", table)
                .isNotEmpty();
        RowSetDiff diff = TemporalRowSetDiffer.compare(mapping, reference, adapter);
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

    private static ClassificationResultList allResultVersions()
    {
        ClassificationResultList list = ClassificationResultFinder.findMany(
                ClassificationResultFinder.processingDate().equalsEdgePoint());
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
        InputStream in = ClassifierBoundPortalTest.class.getResourceAsStream(path);
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

    static final class BoundEntity
    {
        final EntityMapping mapping;
        final PhysicalDesign design;
        final ItemCodec codec;
        final DynamoDbWriter writer;
        final DynamoDbPersister adapter;
        final MithraAbstractObjectPortal portal;
        final MithraObjectReader jdbcReader;
        private final LocalDynamoDb ddb;

        private BoundEntity(LocalDynamoDb ddb,
                            EntityMapping mapping,
                            PhysicalDesign design,
                            ItemCodec codec,
                            DynamoDbWriter writer,
                            DynamoDbPersister adapter,
                            MithraAbstractObjectPortal portal,
                            MithraObjectReader jdbcReader)
        {
            this.ddb = ddb;
            this.mapping = mapping;
            this.design = design;
            this.codec = codec;
            this.writer = writer;
            this.adapter = adapter;
            this.portal = portal;
            this.jdbcReader = jdbcReader;
        }

        static BoundEntity open(LocalDynamoDb ddb,
                                EntityMapping mapping,
                                RelatedFinder finder,
                                List<GsiSpec> gsis,
                                boolean allowTableScan)
        {
            PhysicalDesign.Builder designBuilder = PhysicalDesign.builder(mapping).infinityFrom(finder);
            for (int i = 0; i < gsis.size(); i++)
            {
                designBuilder.addGsi(gsis.get(i));
            }
            PhysicalDesign design = designBuilder.build();
            ItemCodec codec = new ItemCodec(mapping);
            DynamoDbWriter writer = new DynamoDbWriter(ddb.client(), mapping, codec,
                    new DefaultKeyStrategy(), design.gsis());
            PlannerConfig plannerConfig = PlannerConfig.builder()
                    .allowTableScan(allowTableScan)
                    .build();
            DynamoDbPersister adapter = new DynamoDbPersister(
                    finder, mapping, writer,
                    new QueryPlanner(), new QueryPlanExecutor(ddb.client(), codec),
                    design, plannerConfig);
            MithraAbstractObjectPortal portal =
                    (MithraAbstractObjectPortal) finder.getMithraObjectPortal();
            MithraObjectReader jdbcReader = portal.getDatabaseObject();
            return new BoundEntity(ddb, mapping, design, codec, writer, adapter, portal, jdbcReader);
        }

        void bind()
        {
            portal.setMithraObjectReader(adapter);
        }

        void unbind()
        {
            portal.setMithraObjectReader(jdbcReader);
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

    /**
     * Records every persister SPI method Reladomo actually invoked. Same contract as
     * {@code findermatrix.RecordingPersister}: forwards, never swallows.
     */
    static final class SpiRecorder implements InvocationHandler
    {
        private final DynamoDbPersister real;
        private final Set<String> methods = Collections.synchronizedSet(new LinkedHashSet<String>());
        private final MithraObjectReader proxy;

        SpiRecorder(DynamoDbPersister real)
        {
            this.real = real;
            this.proxy = (MithraObjectReader) Proxy.newProxyInstance(
                    DynamoDbPersister.class.getClassLoader(),
                    new Class[] {MithraObjectReader.class, MithraDatedObjectPersister.class},
                    this);
        }

        MithraObjectReader proxy()
        {
            return proxy;
        }

        Set<String> methodsReached()
        {
            return new LinkedHashSet<String>(methods);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
        {
            String name = method.getName();
            if (!"equals".equals(name) && !"hashCode".equals(name) && !"toString".equals(name))
            {
                methods.add(name);
            }
            try
            {
                return method.invoke(real, args);
            }
            catch (InvocationTargetException e)
            {
                Throwable cause = e.getCause();
                if (cause != null)
                {
                    throw cause;
                }
                throw e;
            }
        }
    }
}
