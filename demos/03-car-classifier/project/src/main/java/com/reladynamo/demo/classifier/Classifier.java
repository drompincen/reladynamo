package com.reladynamo.demo.classifier;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.MithraTransaction;
import com.gs.fw.common.mithra.TransactionalCommand;
import com.reladynamo.demo.classifier.domain.Car;
import com.reladynamo.demo.classifier.domain.ClassificationResult;
import com.reladynamo.demo.classifier.domain.ClassificationRule;
import com.reladynamo.demo.classifier.domain.ClassificationRuleFinder;
import com.reladynamo.demo.classifier.domain.ClassificationRuleList;
import com.reladynamo.demo.classifier.domain.RuleCriterion;

import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads the rule set that is business-valid at {@code asOfDate} (current processing
 * knowledge unless the caller pinned a processing time) and picks the highest-priority
 * match. Ties break to the lowest {@code ruleId}.
 */
public final class Classifier
{
    private static final AtomicInteger RESULT_IDS = new AtomicInteger(1);

    public static void resetResultIds()
    {
        RESULT_IDS.set(1);
    }

    public Classification classify(final Car car, final Timestamp asOfDate)
    {
        return MithraManagerProvider.getMithraManager().executeTransactionalCommand(new TransactionalCommand<Classification>()
        {
            @Override
            public Classification executeTransaction(MithraTransaction tx)
            {
                return classifyInTransaction(car, asOfDate, tx);
            }
        });
    }

    private Classification classifyInTransaction(Car car, Timestamp asOfDate, MithraTransaction tx)
    {
        ClassificationRuleList rules = ClassificationRuleFinder.findMany(
                ClassificationRuleFinder.businessDate().eq(asOfDate)
                        .and(ClassificationRuleFinder.active().eq(true)));
        rules.deepFetch(ClassificationRuleFinder.criteria());

        ClassificationRule winner = null;
        for (int i = 0; i < rules.size(); i++)
        {
            ClassificationRule rule = rules.get(i);
            if (!matches(car, rule))
            {
                continue;
            }
            if (winner == null
                    || rule.getPriority() > winner.getPriority()
                    || (rule.getPriority() == winner.getPriority() && rule.getRuleId() < winner.getRuleId()))
            {
                winner = rule;
            }
        }

        String label = winner == null ? Labels.UNCLASSIFIED : winner.getResultLabel();
        int ruleId = winner == null ? 0 : winner.getRuleId();
        String ruleName = winner == null ? "-" : winner.getRuleName();

        int resultId = RESULT_IDS.getAndIncrement();
        ClassificationResult audit = new ClassificationResult();
        audit.setResultId(resultId);
        audit.setCarId(car.getCarId());
        audit.setRuleId(ruleId);
        audit.setResultLabel(label);
        audit.setEvaluatedAsOfDate(asOfDate);
        audit.setEvaluatedTime(new Timestamp(tx.getProcessingStartTime()));
        audit.insert();

        return new Classification(car.getCarId(), asOfDate, label, ruleId, ruleName, resultId);
    }

    static boolean matches(Car car, ClassificationRule rule)
    {
        if (rule.getCriteria().isEmpty())
        {
            return false;
        }
        for (int i = 0; i < rule.getCriteria().size(); i++)
        {
            if (!criterionMatches(car, rule.getCriteria().get(i)))
            {
                return false;
            }
        }
        return true;
    }

    static boolean criterionMatches(Car car, RuleCriterion criterion)
    {
        String operator = criterion.getOperator();
        String attributeName = criterion.getAttributeName();
        if (isNumericAttribute(attributeName))
        {
            int actual = numericValue(car, attributeName);
            if ("EQ".equals(operator))
            {
                return actual == criterion.getValueNumericLow();
            }
            if ("NE".equals(operator))
            {
                return actual != criterion.getValueNumericLow();
            }
            if ("LT".equals(operator))
            {
                return actual < criterion.getValueNumericLow();
            }
            if ("GT".equals(operator))
            {
                return actual > criterion.getValueNumericLow();
            }
            if ("BETWEEN".equals(operator))
            {
                return actual >= criterion.getValueNumericLow() && actual <= criterion.getValueNumericHigh();
            }
            if ("IN".equals(operator))
            {
                return numericIn(actual, criterion.getValueText());
            }
            throw new IllegalArgumentException("Unsupported numeric operator: " + operator);
        }

        String actual = textValue(car, attributeName);
        if ("EQ".equals(operator))
        {
            return actual.equals(criterion.getValueText());
        }
        if ("NE".equals(operator))
        {
            return !actual.equals(criterion.getValueText());
        }
        if ("IN".equals(operator))
        {
            return textIn(actual, criterion.getValueText());
        }
        throw new IllegalArgumentException("Unsupported text operator: " + operator);
    }

    private static boolean isNumericAttribute(String attributeName)
    {
        return "year".equals(attributeName) || "wheelCount".equals(attributeName);
    }

    private static int numericValue(Car car, String attributeName)
    {
        if ("year".equals(attributeName))
        {
            return car.getYear();
        }
        if ("wheelCount".equals(attributeName))
        {
            return car.getWheelCount();
        }
        throw new IllegalArgumentException("Not a numeric car attribute: " + attributeName);
    }

    private static String textValue(Car car, String attributeName)
    {
        if ("make".equals(attributeName))
        {
            return car.getMake();
        }
        if ("model".equals(attributeName))
        {
            return car.getModel();
        }
        if ("colorCode".equals(attributeName))
        {
            return car.getColorCode();
        }
        if ("bodyStyle".equals(attributeName))
        {
            return car.getBodyStyle();
        }
        if ("engineCode".equals(attributeName))
        {
            return car.getEngineCode();
        }
        throw new IllegalArgumentException("Not a text car attribute: " + attributeName);
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
}
