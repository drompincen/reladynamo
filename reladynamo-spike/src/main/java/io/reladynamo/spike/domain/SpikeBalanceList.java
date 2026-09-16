package io.reladynamo.spike.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class SpikeBalanceList extends SpikeBalanceListAbstract
{
	public SpikeBalanceList()
	{
		super();
	}

	public SpikeBalanceList(int initialSize)
	{
		super(initialSize);
	}

	public SpikeBalanceList(Collection c)
	{
		super(c);
	}

	public SpikeBalanceList(Operation operation)
	{
		super(operation);
	}
}
