package com.reladynamo.demo.classifier;

import com.reladynamo.demo.classifier.domain.Car;

import java.sql.Timestamp;

/**
 * Outcome of {@link Classifier#classify(Car, Timestamp)}. Not a persisted Reladomo type;
 * the audit row lives in {@code ClassificationResult}.
 */
public final class Classification
{
    private final int carId;
    private final Timestamp asOfDate;
    private final String resultLabel;
    private final int ruleId;
    private final String ruleName;
    private final int resultId;

    public Classification(int carId, Timestamp asOfDate, String resultLabel, int ruleId, String ruleName, int resultId)
    {
        this.carId = carId;
        this.asOfDate = asOfDate;
        this.resultLabel = resultLabel;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.resultId = resultId;
    }

    public int getCarId()
    {
        return carId;
    }

    public Timestamp getAsOfDate()
    {
        return asOfDate;
    }

    public String getResultLabel()
    {
        return resultLabel;
    }

    public int getRuleId()
    {
        return ruleId;
    }

    public String getRuleName()
    {
        return ruleName;
    }

    public int getResultId()
    {
        return resultId;
    }
}
