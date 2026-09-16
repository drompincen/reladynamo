package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class KennelList extends KennelListAbstract
{
	public KennelList()
	{
		super();
	}

	public KennelList(int initialSize)
	{
		super(initialSize);
	}

	public KennelList(Collection c)
	{
		super(c);
	}

	public KennelList(Operation operation)
	{
		super(operation);
	}
}
