package io.reladynamo.ddb.differential.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class DiffBalanceList extends DiffBalanceListAbstract
{
	public DiffBalanceList()
	{
		super();
	}

	public DiffBalanceList(int initialSize)
	{
		super(initialSize);
	}

	public DiffBalanceList(Collection c)
	{
		super(c);
	}

	public DiffBalanceList(Operation operation)
	{
		super(operation);
	}
}
