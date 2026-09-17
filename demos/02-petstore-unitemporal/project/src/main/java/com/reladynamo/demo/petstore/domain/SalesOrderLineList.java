package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class SalesOrderLineList extends SalesOrderLineListAbstract
{
	public SalesOrderLineList()
	{
		super();
	}

	public SalesOrderLineList(int initialSize)
	{
		super(initialSize);
	}

	public SalesOrderLineList(Collection c)
	{
		super(c);
	}

	public SalesOrderLineList(Operation operation)
	{
		super(operation);
	}
}
