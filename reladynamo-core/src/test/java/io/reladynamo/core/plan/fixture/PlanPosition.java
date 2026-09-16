package io.reladynamo.core.plan.fixture;

import java.sql.Timestamp;

public class PlanPosition extends PlanPositionAbstract
{
    public PlanPosition(Timestamp businessDate, Timestamp processingDate)
    {
        super(businessDate, processingDate);
    }

    public PlanPosition(Timestamp businessDate)
    {
        super(businessDate);
    }
}
