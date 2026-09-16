package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class PriceBookList extends PriceBookListAbstract
{
	public PriceBookList()
	{
		super();
	}

	public PriceBookList(int initialSize)
	{
		super(initialSize);
	}

	public PriceBookList(Collection c)
	{
		super(c);
	}

	public PriceBookList(Operation operation)
	{
		super(operation);
	}
}
