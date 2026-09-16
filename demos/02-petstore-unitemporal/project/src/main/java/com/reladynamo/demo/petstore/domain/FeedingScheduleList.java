package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class FeedingScheduleList extends FeedingScheduleListAbstract
{
	public FeedingScheduleList()
	{
		super();
	}

	public FeedingScheduleList(int initialSize)
	{
		super(initialSize);
	}

	public FeedingScheduleList(Collection c)
	{
		super(c);
	}

	public FeedingScheduleList(Operation operation)
	{
		super(operation);
	}
}
