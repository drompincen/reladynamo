package io.reladynamo.ddb.differential.domain;

import com.gs.fw.finder.Operation;

import java.util.Collection;

public class DiffFinderValueList extends DiffFinderValueListAbstract
{
    public DiffFinderValueList()
    {
        super();
    }

    public DiffFinderValueList(int initialSize)
    {
        super(initialSize);
    }

    public DiffFinderValueList(Collection c)
    {
        super(c);
    }

    public DiffFinderValueList(Operation operation)
    {
        super(operation);
    }
}
