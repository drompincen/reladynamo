package com.reladynamo.demo.classifier;

import com.gs.fw.common.mithra.util.DefaultInfinityTimestamp;
import com.reladynamo.demo.classifier.domain.Car;
import com.reladynamo.demo.classifier.domain.CarFinder;
import com.reladynamo.demo.classifier.domain.ClassificationResult;
import com.reladynamo.demo.classifier.domain.ClassificationResultFinder;
import com.reladynamo.demo.classifier.domain.ClassificationRule;
import com.reladynamo.demo.classifier.domain.ClassificationRuleFinder;
import com.reladynamo.demo.classifier.util.Dates;
import com.reladynamo.demo.classifier.util.ReladomoRuntime;
import com.reladynamo.demo.classifier.util.Transactions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierTest
{
    private Classifier classifier;

    @BeforeAll
    static void startReladomo()
    {
        ReladomoRuntime.start();
    }

    @BeforeEach
    void seed()
    {
        ReladomoRuntime.wipeData();
        DemoSeed.insertCanonical();
        classifier = new Classifier();
    }

    @Test
    void should_progress_mr2_from_cool_through_eighties_cool_to_retro()
    {
        Car mr2 = car(DemoSeed.MR2_ID);

        assertThat(classifier.classify(mr2, Dates.AS_OF_2012).getResultLabel()).isEqualTo(Labels.COOL);
        assertThat(classifier.classify(mr2, Dates.AS_OF_2016).getResultLabel()).isEqualTo(Labels.EIGHTIES_COOL);
        assertThat(classifier.classify(mr2, Dates.AS_OF_2021).getResultLabel()).isEqualTo(Labels.RETRO);
        assertThat(classifier.classify(mr2, Dates.TODAY).getResultLabel()).isEqualTo(Labels.RETRO);
    }

    @Test
    void should_keep_1985_sedan_cool_until_r3_because_r2_requires_coupe()
    {
        Car corolla = car(DemoSeed.COROLLA_ID);

        assertThat(classifier.classify(corolla, Dates.AS_OF_2012).getResultLabel()).isEqualTo(Labels.COOL);
        assertThat(classifier.classify(corolla, Dates.AS_OF_2016).getResultLabel()).isEqualTo(Labels.COOL);
        assertThat(classifier.classify(corolla, Dates.AS_OF_2021).getResultLabel()).isEqualTo(Labels.RETRO);
    }

    @Test
    void should_classify_1957_bel_air_as_vintage_only_after_r5_exists()
    {
        Car belAir = car(DemoSeed.BEL_AIR_ID);

        assertThat(classifier.classify(belAir, Dates.AS_OF_2021).getResultLabel()).isEqualTo(Labels.CLASSIC);
        assertThat(classifier.classify(belAir, Dates.TODAY).getResultLabel()).isEqualTo(Labels.VINTAGE);
    }

    @Test
    void should_classify_three_wheeler_as_exotic_even_when_eighties_rules_also_match()
    {
        assertThat(classifier.classify(car(DemoSeed.ROBIN_ID), Dates.AS_OF_2012).getResultLabel())
                .isEqualTo(Labels.EXOTIC);
        assertThat(classifier.classify(car(DemoSeed.ROBIN_ID), Dates.TODAY).getResultLabel())
                .isEqualTo(Labels.EXOTIC);
    }

    @Test
    void should_leave_2005_civic_unclassified()
    {
        assertThat(classifier.classify(car(DemoSeed.CIVIC_ID), Dates.TODAY).getResultLabel())
                .isEqualTo(Labels.UNCLASSIFIED);
    }

    @Test
    void should_classify_2018_tesla_as_modern_only_after_r7_exists()
    {
        Car tesla = car(DemoSeed.TESLA_ID);

        assertThat(classifier.classify(tesla, Dates.AS_OF_2016).getResultLabel()).isEqualTo(Labels.UNCLASSIFIED);
        assertThat(classifier.classify(tesla, Dates.AS_OF_2021).getResultLabel()).isEqualTo(Labels.MODERN);
    }

    @Test
    void should_stop_using_r1_after_it_is_terminated()
    {
        Car sedan = car(DemoSeed.COROLLA_ID);

        assertThat(classifier.classify(sedan, Dates.AS_OF_2013).getResultLabel()).isEqualTo(Labels.COOL);

        DemoSeed.terminateR1AsOf2014();

        assertThat(classifier.classify(sedan, Dates.AS_OF_2013).getResultLabel()).isEqualTo(Labels.COOL);
        assertThat(classifier.classify(sedan, Dates.AS_OF_2014).getResultLabel()).isEqualTo(Labels.UNCLASSIFIED);
        assertThat(classifier.classify(car(DemoSeed.MR2_ID), Dates.AS_OF_2016).getResultLabel())
                .isEqualTo(Labels.EIGHTIES_COOL);
    }

    @Test
    void should_classify_every_seeded_car_as_of_2012_2016_2021_and_today()
    {
        String[][] expected = {
                {Labels.COOL, Labels.EIGHTIES_COOL, Labels.RETRO, Labels.RETRO},
                {Labels.COOL, Labels.COOL, Labels.RETRO, Labels.RETRO},
                {Labels.CLASSIC, Labels.CLASSIC, Labels.CLASSIC, Labels.CLASSIC},
                {Labels.CLASSIC, Labels.CLASSIC, Labels.CLASSIC, Labels.VINTAGE},
                {Labels.UNCLASSIFIED, Labels.UNCLASSIFIED, Labels.MODERN, Labels.MODERN},
                {Labels.UNCLASSIFIED, Labels.UNCLASSIFIED, Labels.UNCLASSIFIED, Labels.UNCLASSIFIED},
                {Labels.EXOTIC, Labels.EXOTIC, Labels.EXOTIC, Labels.EXOTIC},
                {Labels.UNCLASSIFIED, Labels.UNCLASSIFIED, Labels.UNCLASSIFIED, Labels.UNCLASSIFIED},
                {Labels.CLASSIC, Labels.CLASSIC, Labels.CLASSIC, Labels.CLASSIC},
                {Labels.UNCLASSIFIED, Labels.UNCLASSIFIED, Labels.MODERN, Labels.MODERN}
        };

        for (int carId = 1; carId <= 10; carId++)
        {
            Car car = car(carId);
            assertThat(classifier.classify(car, Dates.AS_OF_2012).getResultLabel())
                    .as("car %s as of 2012", car.displayName())
                    .isEqualTo(expected[carId - 1][0]);
            assertThat(classifier.classify(car, Dates.AS_OF_2016).getResultLabel())
                    .as("car %s as of 2016", car.displayName())
                    .isEqualTo(expected[carId - 1][1]);
            assertThat(classifier.classify(car, Dates.AS_OF_2021).getResultLabel())
                    .as("car %s as of 2021", car.displayName())
                    .isEqualTo(expected[carId - 1][2]);
            assertThat(classifier.classify(car, Dates.TODAY).getResultLabel())
                    .as("car %s as of today", car.displayName())
                    .isEqualTo(expected[carId - 1][3]);
        }
    }

    @Test
    void should_diverge_from_audit_after_processing_date_correction()
    {
        DemoSeed.insertWrongNinetiesRule();
        Car miata = car(DemoSeed.MIATA_ID);

        final Classification[] saidThen = new Classification[1];
        Transactions.at(Dates.WRONG_RULE_EVALUATED, new Runnable()
        {
            @Override
            public void run()
            {
                saidThen[0] = classifier.classify(miata, Dates.AS_OF_2012);
            }
        });
        assertThat(saidThen[0].getResultLabel()).isEqualTo(Labels.CLASSIC);

        DemoSeed.correctWrongNinetiesRuleToEconomy();

        ClassificationResult audit = ClassificationResultFinder.findOne(
                ClassificationResultFinder.resultId().eq(saidThen[0].getResultId())
                        .and(ClassificationResultFinder.processingDate().eq(
                                DefaultInfinityTimestamp.getDefaultInfinity())));
        assertThat(audit.getResultLabel()).isEqualTo(Labels.CLASSIC);

        final Classification[] nowThink = new Classification[1];
        Transactions.at(Dates.AFTER_CORRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                nowThink[0] = classifier.classify(miata, Dates.AS_OF_2012);
            }
        });
        assertThat(nowThink[0].getResultLabel()).isEqualTo(Labels.ECONOMY);

        ClassificationRule thenRule = ClassificationRuleFinder.findByPrimaryKey(
                DemoSeed.R8_WRONG, Dates.AS_OF_2012, Dates.WRONG_RULE_EVALUATED);
        ClassificationRule nowRule = ClassificationRuleFinder.findByPrimaryKey(
                DemoSeed.R8_WRONG, Dates.AS_OF_2012, DefaultInfinityTimestamp.getDefaultInfinity());
        assertThat(thenRule.getResultLabel()).isEqualTo(Labels.CLASSIC);
        assertThat(nowRule.getResultLabel()).isEqualTo(Labels.ECONOMY);
    }

    private static Car car(int carId)
    {
        return CarFinder.findOne(CarFinder.carId().eq(carId));
    }
}
