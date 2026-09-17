package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class SalesRepList extends SalesRepListAbstract
{
	public SalesRepList()
	{
		super();
	}

	public SalesRepList(int initialSize)
	{
		super(initialSize);
	}

	public SalesRepList(Collection c)
	{
		super(c);
	}

	public SalesRepList(Operation operation)
	{
		super(operation);
	}
}
