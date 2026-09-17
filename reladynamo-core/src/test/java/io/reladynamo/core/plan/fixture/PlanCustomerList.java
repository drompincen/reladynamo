package io.reladynamo.core.plan.fixture;

import com.gs.fw.finder.Operation;

import java.util.Collection;

public class PlanCustomerList extends PlanCustomerListAbstract
{
    public PlanCustomerList()
    {
        super();
    }

    public PlanCustomerList(int initialSize)
    {
        super(initialSize);
    }

    public PlanCustomerList(Collection c)
    {
        super(c);
    }

    public PlanCustomerList(Operation operation)
    {
        super(operation);
    }
}
