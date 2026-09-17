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

import java.util.TimeZone;

/**
 * Demo 3: the rules are bitemporal, not the cars. A 1985 coupe is COOL in 2012,
 * EIGHTIES_COOL in 2016, and RETRO today — because the rule set changed.
 */
public final class CarClassifierDemo
{
    private CarClassifierDemo()
    {
    }

    public static void main(String[] args)
    {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ReladomoRuntime.start();
        ReladomoRuntime.wipeData();
        DemoSeed.insertCanonical();

        Classifier classifier = new Classifier();
        System.out.println();
        System.out.println("Car classifier - bitemporal decision table");
        System.out.println("The cars never change. The rules do.");
        System.out.println();
        printSideBySideTable(classifier);
        printMr2Callout(classifier);
        printRetroactiveCorrection(classifier);
        printRuleExpiry(classifier);
        System.exit(0);
    }

    private static void printSideBySideTable(Classifier classifier)
    {
        System.out.println("Same 10 cars, four as-of dates (current processing knowledge)");
        System.out.println(repeat('-', 98));
        System.out.println(String.format("%-34s %-15s %-15s %-15s %-15s",
                "Car", "2012-06-01", "2016-06-01", "2021-06-01", "2026-09-12"));
        System.out.println(repeat('-', 98));

        Transactions.at(Dates.TODAY, new Runnable()
        {
            @Override
            public void run()
            {
                for (int carId = 1; carId <= 10; carId++)
                {
                    Car car = CarFinder.findOne(CarFinder.carId().eq(carId));
                    String c2012 = classifier.classify(car, Dates.AS_OF_2012).getResultLabel();
                    String c2016 = classifier.classify(car, Dates.AS_OF_2016).getResultLabel();
                    String c2021 = classifier.classify(car, Dates.AS_OF_2021).getResultLabel();
                    String cToday = classifier.classify(car, Dates.TODAY).getResultLabel();
                    System.out.println(String.format("%-34s %-15s %-15s %-15s %-15s",
                            car.displayName(), c2012, c2016, c2021, cToday));
                }
            }
        });
        System.out.println(repeat('-', 98));
        System.out.println();
    }

    private static void printMr2Callout(Classifier classifier)
    {
        Car mr2 = CarFinder.findOne(CarFinder.carId().eq(DemoSeed.MR2_ID));
        System.out.println("The point, in one car: " + mr2.displayName());
        System.out.println("  as of 2012-06-01  ->  " + classifier.classify(mr2, Dates.AS_OF_2012).getResultLabel()
                + "   (only R1 exists yet)");
        System.out.println("  as of 2016-06-01  ->  " + classifier.classify(mr2, Dates.AS_OF_2016).getResultLabel()
                + "   (R2 is more specific)");
        System.out.println("  as of 2026-09-12  ->  " + classifier.classify(mr2, Dates.TODAY).getResultLabel()
                + "   (R3 supersedes; tastes moved on)");
        System.out.println();
    }

    private static void printRetroactiveCorrection(Classifier classifier)
    {
        System.out.println("Retroactive rule correction (the bitemporal payoff)");
        System.out.println(repeat('-', 98));
        System.out.println("R8 was recorded as business-valid from 2010: year 1990-1999 -> CLASSIC.");
        System.out.println("That label was wrong when written. We classified the 1994 Mazda MX-5 as of");
        System.out.println("2012 and stored the audit row. Later we correct R8 to ECONOMY (processing-date");
        System.out.println("correction; business-valid-from is still 2010-01-01). The car did not change.");
        System.out.println();

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

        DemoSeed.correctWrongNinetiesRuleToEconomy();

        final Classification[] nowThink = new Classification[1];
        Transactions.at(Dates.AFTER_CORRECTION, new Runnable()
        {
            @Override
            public void run()
            {
                nowThink[0] = classifier.classify(miata, Dates.AS_OF_2012);
            }
        });
        ClassificationResult audit = ClassificationResultFinder.findOne(
                ClassificationResultFinder.resultId().eq(saidThen[0].getResultId())
                        .and(ClassificationResultFinder.processingDate().eq(
                                DefaultInfinityTimestamp.getDefaultInfinity())));

        ClassificationRule thenRule = ClassificationRuleFinder.findByPrimaryKey(
                DemoSeed.R8_WRONG, Dates.AS_OF_2012, Dates.WRONG_RULE_EVALUATED);
        ClassificationRule nowRule = ClassificationRuleFinder.findByPrimaryKey(
                DemoSeed.R8_WRONG, Dates.AS_OF_2012, DefaultInfinityTimestamp.getDefaultInfinity());

        System.out.println(String.format("  %-48s %s", "what we said then:",
                audit.getResultLabel() + "  (audit row #" + audit.getResultId()
                        + ", evaluated as of 2012-06-01)"));
        System.out.println(String.format("  %-48s %s", "what we now think we should have said then:",
                nowThink[0].getResultLabel() + "  (re-run classify(miata, 2012-06-01) after the correction)"));
        System.out.println();
        System.out.println("  R8 as of business 2012 / processing 2026-09-12 11:00 : " + thenRule.getResultLabel());
        System.out.println("  R8 as of business 2012 / processing now              : " + nowRule.getResultLabel());
        System.out.println();
    }

    private static void printRuleExpiry(Classifier classifier)
    {
        System.out.println("Rule expiry");
        System.out.println(repeat('-', 98));
        Car sedan = CarFinder.findOne(CarFinder.carId().eq(DemoSeed.COROLLA_ID));
        String before = classifier.classify(sedan, Dates.AS_OF_2013).getResultLabel();
        DemoSeed.terminateR1AsOf2014();
        String after = classifier.classify(sedan, Dates.AS_OF_2014).getResultLabel();
        System.out.println(sedan.displayName() + " uses R1 (1980-89 -> COOL) until we terminate R1 on 2014-01-01.");
        System.out.println("  as of 2013-06-01 (R1 still valid) : " + before);
        System.out.println("  as of 2014-06-01 (R1 terminated)  : " + after);
        System.out.println();
    }

    private static String repeat(char ch, int n)
    {
        StringBuilder builder = new StringBuilder(n);
        for (int i = 0; i < n; i++)
        {
            builder.append(ch);
        }
        return builder.toString();
    }
}
