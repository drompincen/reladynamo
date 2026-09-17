package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class SalesOrderList extends SalesOrderListAbstract
{
	public SalesOrderList()
	{
		super();
	}

	public SalesOrderList(int initialSize)
	{
		super(initialSize);
	}

	public SalesOrderList(Collection c)
	{
		super(c);
	}

	public SalesOrderList(Operation operation)
	{
		super(operation);
	}
}
