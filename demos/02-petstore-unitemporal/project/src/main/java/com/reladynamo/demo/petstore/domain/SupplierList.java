package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class SupplierList extends SupplierListAbstract
{
	public SupplierList()
	{
		super();
	}

	public SupplierList(int initialSize)
	{
		super(initialSize);
	}

	public SupplierList(Collection c)
	{
		super(c);
	}

	public SupplierList(Operation operation)
	{
		super(operation);
	}
}
