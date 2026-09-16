package io.reladynamo.core.plan.fixture;

import com.gs.fw.finder.Operation;

import java.util.Collection;

public class PlanPositionList extends PlanPositionListAbstract
{
    public PlanPositionList()
    {
        super();
    }

    public PlanPositionList(int initialSize)
    {
        super(initialSize);
    }

    public PlanPositionList(Collection c)
    {
        super(c);
    }

    public PlanPositionList(Operation operation)
    {
        super(operation);
    }
}
