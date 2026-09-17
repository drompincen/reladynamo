package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class TerritoryList extends TerritoryListAbstract
{
	public TerritoryList()
	{
		super();
	}

	public TerritoryList(int initialSize)
	{
		super(initialSize);
	}

	public TerritoryList(Collection c)
	{
		super(c);
	}

	public TerritoryList(Operation operation)
	{
		super(operation);
	}
}
