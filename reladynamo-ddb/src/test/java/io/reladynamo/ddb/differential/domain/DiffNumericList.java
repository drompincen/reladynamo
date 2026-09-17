package io.reladynamo.ddb.differential.domain;

import com.gs.fw.finder.Operation;

import java.util.Collection;

public class DiffNumericList extends DiffNumericListAbstract
{
    public DiffNumericList()
    {
        super();
    }

    public DiffNumericList(int initialSize)
    {
        super(initialSize);
    }

    public DiffNumericList(Collection c)
    {
        super(c);
    }

    public DiffNumericList(Operation operation)
    {
        super(operation);
    }
}
