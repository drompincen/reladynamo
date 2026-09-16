package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class PriceBookEntryList extends PriceBookEntryListAbstract
{
	public PriceBookEntryList()
	{
		super();
	}

	public PriceBookEntryList(int initialSize)
	{
		super(initialSize);
	}

	public PriceBookEntryList(Collection c)
	{
		super(c);
	}

	public PriceBookEntryList(Operation operation)
	{
		super(operation);
	}
}
