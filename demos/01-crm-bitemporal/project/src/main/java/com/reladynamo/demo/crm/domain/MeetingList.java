package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class MeetingList extends MeetingListAbstract
{
	public MeetingList()
	{
		super();
	}

	public MeetingList(int initialSize)
	{
		super(initialSize);
	}

	public MeetingList(Collection c)
	{
		super(c);
	}

	public MeetingList(Operation operation)
	{
		super(operation);
	}
}
