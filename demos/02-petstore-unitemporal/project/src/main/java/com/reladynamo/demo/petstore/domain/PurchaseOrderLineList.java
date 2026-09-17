package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class PurchaseOrderLineList extends PurchaseOrderLineListAbstract
{
	public PurchaseOrderLineList()
	{
		super();
	}

	public PurchaseOrderLineList(int initialSize)
	{
		super(initialSize);
	}

	public PurchaseOrderLineList(Collection c)
	{
		super(c);
	}

	public PurchaseOrderLineList(Operation operation)
	{
		super(operation);
	}
}
