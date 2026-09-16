package io.reladynamo.core.plan.fixture;

import java.sql.Timestamp;

public class PlanRule extends PlanRuleAbstract
{
    public PlanRule(Timestamp businessDate, Timestamp processingDate)
    {
        super(businessDate, processingDate);
    }

    public PlanRule(Timestamp businessDate)
    {
        super(businessDate);
    }
}
