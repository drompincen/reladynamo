package io.reladynamo.core.plan.fixture;

import com.gs.fw.finder.Operation;

import java.util.Collection;

public class PlanRuleList extends PlanRuleListAbstract
{
    public PlanRuleList()
    {
        super();
    }

    public PlanRuleList(int initialSize)
    {
        super(initialSize);
    }

    public PlanRuleList(Collection c)
    {
        super(c);
    }

    public PlanRuleList(Operation operation)
    {
        super(operation);
    }
}
