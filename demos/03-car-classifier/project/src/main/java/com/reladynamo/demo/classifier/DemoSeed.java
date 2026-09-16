package com.reladynamo.demo.classifier;

import com.gs.fw.common.mithra.util.DefaultInfinityTimestamp;
import com.reladynamo.demo.classifier.domain.Car;
import com.reladynamo.demo.classifier.domain.ClassificationRule;
import com.reladynamo.demo.classifier.domain.ClassificationRuleFinder;
import com.reladynamo.demo.classifier.domain.ResultLabel;
import com.reladynamo.demo.classifier.domain.RuleCriterion;
import com.reladynamo.demo.classifier.util.Dates;
import com.reladynamo.demo.classifier.util.Transactions;

import java.sql.Timestamp;

/**
 * Canonical cars plus the spec decision table. Each rule is inserted with its
 * business-valid-from date so the rule set genuinely differs by as-of date.
 */
public final class DemoSeed
{
    public static final int MR2_ID = 1;
    public static final int COROLLA_ID = 2;
    public static final int BEETLE_ID = 3;
    public static final int BEL_AIR_ID = 4;
    public static final int TESLA_ID = 5;
    public static final int CIVIC_ID = 6;
    public static final int ROBIN_ID = 7;
    public static final int MIATA_ID = 8;
    public static final int MUSTANG_ID = 9;
    public static final int PORSCHE_ID = 10;

    public static final int R1 = 1;
    public static final int R2 = 2;
    public static final int R3 = 3;
    public static final int R4 = 4;
    public static final int R5 = 5;
    public static final int R6 = 6;
    public static final int R7 = 7;
    public static final int R8_WRONG = 8;

    private DemoSeed()
    {
    }

    public static void insertCanonical()
    {
        Transactions.at(Dates.FROM_2010, new Runnable()
        {
            @Override
            public void run()
            {
                insertLabels();
                insertCars();
                insertEightiesCool(R1, 40, Labels.COOL, 11);
                insertClassic();
                insertExotic();
            }
        });
        Transactions.at(Dates.FROM_2015, new Runnable()
        {
            @Override
            public void run()
            {
                insertEightiesCoupe();
            }
        });
        Transactions.at(Dates.FROM_2018, new Runnable()
        {
            @Override
            public void run()
            {
                insertModern();
            }
        });
        Transactions.at(Dates.FROM_2020, new Runnable()
        {
            @Override
            public void run()
            {
                insertEightiesCool(R3, 80, Labels.RETRO, 31);
            }
        });
        Transactions.at(Dates.FROM_2022, new Runnable()
        {
            @Override
            public void run()
            {
                insertVintage();
            }
        });
    }

    public static void insertWrongNinetiesRule()
    {
        Transactions.at(Dates.WRONG_RULE_LEARNED, new Runnable()
        {
            @Override
            public void run()
            {
                ClassificationRule rule = new ClassificationRule(Dates.FROM_2010);
                rule.setRuleId(R8_WRONG);
                rule.setRuleName("R8_NINETIES_WERE_CLASSIC");
                rule.setPriority(55);
                rule.setResultLabel(Labels.CLASSIC);
                rule.setActive(true);
                rule.insert();

                RuleCriterion year = new RuleCriterion(Dates.FROM_2010);
                year.setCriterionId(81);
                year.setRuleId(R8_WRONG);
                year.setAttributeName("year");
                year.setOperator("BETWEEN");
                year.setValueNumericLow(1990);
                year.setValueNumericHigh(1999);
                year.insert();
            }
        });
    }

    public static void correctWrongNinetiesRuleToEconomy()
    {
        Transactions.at(Dates.WRONG_RULE_CORRECTED, new Runnable()
        {
            @Override
            public void run()
            {
                ClassificationRule rule = ClassificationRuleFinder.findByPrimaryKey(
                        R8_WRONG, Dates.FROM_2010, DefaultInfinityTimestamp.getDefaultInfinity());
                if (rule == null)
                {
                    throw new IllegalStateException("R8 is missing; cannot correct it");
                }
                rule.setResultLabel(Labels.ECONOMY);
            }
        });
    }

    public static void terminateR1AsOf2014()
    {
        Transactions.at(Dates.TERMINATE_PROCESSING, new Runnable()
        {
            @Override
            public void run()
            {
                ClassificationRule rule = ClassificationRuleFinder.findByPrimaryKey(
                        R1, Dates.TERMINATE_R1_ON, DefaultInfinityTimestamp.getDefaultInfinity());
                if (rule == null)
                {
                    throw new IllegalStateException("R1 is not visible at " + Dates.TERMINATE_R1_ON);
                }
                rule.cascadeTerminate();
            }
        });
    }

    private static void insertLabels()
    {
        label(Labels.COOL, "Cool", "Still fashionable at the time of evaluation.");
        label(Labels.RETRO, "Retro", "Eighties cars, after tastes moved on.");
        label(Labels.CLASSIC, "Classic", "Old enough to be a classic, not yet vintage.");
        label(Labels.EIGHTIES_COOL, "Eighties Cool", "Specific 1980s coupe cool.");
        label(Labels.VINTAGE, "Vintage", "Pre-1960.");
        label(Labels.MODERN, "Modern", "Newer than 2015.");
        label(Labels.ECONOMY, "Economy", "Ordinary daily driver.");
        label(Labels.EXOTIC, "Exotic", "Not a four-wheeler.");
        label(Labels.UNCLASSIFIED, "Unclassified", "No rule matched.");
    }

    private static void insertCars()
    {
        car(MR2_ID, 1985, "Toyota", "MR2", 4, "RED", "COUPE", "4CYL");
        car(COROLLA_ID, 1985, "Toyota", "Corolla", 4, "WHITE", "SEDAN", "4CYL");
        car(BEETLE_ID, 1972, "Volkswagen", "Beetle", 4, "YELLOW", "SEDAN", "4CYL");
        car(BEL_AIR_ID, 1957, "Chevrolet", "Bel Air", 4, "TURQUOISE", "COUPE", "V8");
        car(TESLA_ID, 2018, "Tesla", "Model 3", 4, "BLACK", "SEDAN", "ELECTRIC");
        car(CIVIC_ID, 2005, "Honda", "Civic", 4, "SILVER", "SEDAN", "4CYL");
        car(ROBIN_ID, 1985, "Reliant", "Robin", 3, "BLUE", "SEDAN", "4CYL");
        car(MIATA_ID, 1994, "Mazda", "MX-5", 4, "RED", "COUPE", "4CYL");
        car(MUSTANG_ID, 1965, "Ford", "Mustang", 4, "BLUE", "COUPE", "V8");
        car(PORSCHE_ID, 2020, "Porsche", "911", 4, "YELLOW", "COUPE", "FLAT6");
    }

    private static void insertEightiesCool(int ruleId, int priority, String label, int criterionId)
    {
        ClassificationRule rule = new ClassificationRule(ruleId == R3 ? Dates.FROM_2020 : Dates.FROM_2010);
        rule.setRuleId(ruleId);
        rule.setRuleName(ruleId == R1 ? "R1_EIGHTIES_COOL" : "R3_EIGHTIES_RETRO");
        rule.setPriority(priority);
        rule.setResultLabel(label);
        rule.setActive(true);
        rule.insert();

        Timestamp from = ruleId == R3 ? Dates.FROM_2020 : Dates.FROM_2010;
        RuleCriterion year = new RuleCriterion(from);
        year.setCriterionId(criterionId);
        year.setRuleId(ruleId);
        year.setAttributeName("year");
        year.setOperator("BETWEEN");
        year.setValueNumericLow(1980);
        year.setValueNumericHigh(1989);
        year.insert();
    }

    private static void insertEightiesCoupe()
    {
        ClassificationRule rule = new ClassificationRule(Dates.FROM_2015);
        rule.setRuleId(R2);
        rule.setRuleName("R2_EIGHTIES_COUPE");
        rule.setPriority(70);
        rule.setResultLabel(Labels.EIGHTIES_COOL);
        rule.setActive(true);
        rule.insert();

        RuleCriterion year = new RuleCriterion(Dates.FROM_2015);
        year.setCriterionId(21);
        year.setRuleId(R2);
        year.setAttributeName("year");
        year.setOperator("BETWEEN");
        year.setValueNumericLow(1980);
        year.setValueNumericHigh(1989);
        year.insert();

        RuleCriterion body = new RuleCriterion(Dates.FROM_2015);
        body.setCriterionId(22);
        body.setRuleId(R2);
        body.setAttributeName("bodyStyle");
        body.setOperator("EQ");
        body.setValueText("COUPE");
        body.insert();
    }

    private static void insertClassic()
    {
        ClassificationRule rule = new ClassificationRule(Dates.FROM_2010);
        rule.setRuleId(R4);
        rule.setRuleName("R4_CLASSIC");
        rule.setPriority(50);
        rule.setResultLabel(Labels.CLASSIC);
        rule.setActive(true);
        rule.insert();

        RuleCriterion year = new RuleCriterion(Dates.FROM_2010);
        year.setCriterionId(41);
        year.setRuleId(R4);
        year.setAttributeName("year");
        year.setOperator("LT");
        year.setValueNumericLow(1975);
        year.insert();
    }

    private static void insertVintage()
    {
        ClassificationRule rule = new ClassificationRule(Dates.FROM_2022);
        rule.setRuleId(R5);
        rule.setRuleName("R5_VINTAGE");
        rule.setPriority(90);
        rule.setResultLabel(Labels.VINTAGE);
        rule.setActive(true);
        rule.insert();

        RuleCriterion year = new RuleCriterion(Dates.FROM_2022);
        year.setCriterionId(51);
        year.setRuleId(R5);
        year.setAttributeName("year");
        year.setOperator("LT");
        year.setValueNumericLow(1960);
        year.insert();
    }

    private static void insertExotic()
    {
        ClassificationRule rule = new ClassificationRule(Dates.FROM_2010);
        rule.setRuleId(R6);
        rule.setRuleName("R6_EXOTIC_WHEELS");
        rule.setPriority(100);
        rule.setResultLabel(Labels.EXOTIC);
        rule.setActive(true);
        rule.insert();

        RuleCriterion wheels = new RuleCriterion(Dates.FROM_2010);
        wheels.setCriterionId(61);
        wheels.setRuleId(R6);
        wheels.setAttributeName("wheelCount");
        wheels.setOperator("NE");
        wheels.setValueNumericLow(4);
        wheels.insert();
    }

    private static void insertModern()
    {
        ClassificationRule rule = new ClassificationRule(Dates.FROM_2018);
        rule.setRuleId(R7);
        rule.setRuleName("R7_MODERN");
        rule.setPriority(60);
        rule.setResultLabel(Labels.MODERN);
        rule.setActive(true);
        rule.insert();

        RuleCriterion year = new RuleCriterion(Dates.FROM_2018);
        year.setCriterionId(71);
        year.setRuleId(R7);
        year.setAttributeName("year");
        year.setOperator("GT");
        year.setValueNumericLow(2015);
        year.insert();
    }

    private static void label(String code, String display, String description)
    {
        ResultLabel label = new ResultLabel();
        label.setLabelCode(code);
        label.setDisplayName(display);
        label.setDescription(description);
        label.insert();
    }

    private static void car(int id, int year, String make, String model, int wheels,
                            String color, String body, String engine)
    {
        Car car = new Car();
        car.setCarId(id);
        car.setYear(year);
        car.setMake(make);
        car.setModel(model);
        car.setWheelCount(wheels);
        car.setColorCode(color);
        car.setBodyStyle(body);
        car.setEngineCode(engine);
        car.insert();
    }
}
