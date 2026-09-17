package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class StoreList extends StoreListAbstract
{
	public StoreList()
	{
		super();
	}

	public StoreList(int initialSize)
	{
		super(initialSize);
	}

	public StoreList(Collection c)
	{
		super(c);
	}

	public StoreList(Operation operation)
	{
		super(operation);
	}
}
