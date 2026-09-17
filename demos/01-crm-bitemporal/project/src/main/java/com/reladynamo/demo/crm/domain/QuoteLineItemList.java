package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class QuoteLineItemList extends QuoteLineItemListAbstract
{
	public QuoteLineItemList()
	{
		super();
	}

	public QuoteLineItemList(int initialSize)
	{
		super(initialSize);
	}

	public QuoteLineItemList(Collection c)
	{
		super(c);
	}

	public QuoteLineItemList(Operation operation)
	{
		super(operation);
	}
}
