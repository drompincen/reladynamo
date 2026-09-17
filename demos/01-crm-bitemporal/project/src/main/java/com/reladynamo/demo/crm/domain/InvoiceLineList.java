package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class InvoiceLineList extends InvoiceLineListAbstract
{
	public InvoiceLineList()
	{
		super();
	}

	public InvoiceLineList(int initialSize)
	{
		super(initialSize);
	}

	public InvoiceLineList(Collection c)
	{
		super(c);
	}

	public InvoiceLineList(Operation operation)
	{
		super(operation);
	}
}
