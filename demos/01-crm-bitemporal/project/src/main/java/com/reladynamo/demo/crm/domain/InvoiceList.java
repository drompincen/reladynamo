package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class InvoiceList extends InvoiceListAbstract
{
	public InvoiceList()
	{
		super();
	}

	public InvoiceList(int initialSize)
	{
		super(initialSize);
	}

	public InvoiceList(Collection c)
	{
		super(c);
	}

	public InvoiceList(Operation operation)
	{
		super(operation);
	}
}
