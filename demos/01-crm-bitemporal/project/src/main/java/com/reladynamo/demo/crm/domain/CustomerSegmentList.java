package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class CustomerSegmentList extends CustomerSegmentListAbstract
{
	public CustomerSegmentList()
	{
		super();
	}

	public CustomerSegmentList(int initialSize)
	{
		super(initialSize);
	}

	public CustomerSegmentList(Collection c)
	{
		super(c);
	}

	public CustomerSegmentList(Operation operation)
	{
		super(operation);
	}
}
