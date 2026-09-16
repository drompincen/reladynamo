package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class PurchaseOrderList extends PurchaseOrderListAbstract
{
	public PurchaseOrderList()
	{
		super();
	}

	public PurchaseOrderList(int initialSize)
	{
		super(initialSize);
	}

	public PurchaseOrderList(Collection c)
	{
		super(c);
	}

	public PurchaseOrderList(Operation operation)
	{
		super(operation);
	}
}
