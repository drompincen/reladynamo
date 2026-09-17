package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class StockLevelList extends StockLevelListAbstract
{
	public StockLevelList()
	{
		super();
	}

	public StockLevelList(int initialSize)
	{
		super(initialSize);
	}

	public StockLevelList(Collection c)
	{
		super(c);
	}

	public StockLevelList(Operation operation)
	{
		super(operation);
	}
}
