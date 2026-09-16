package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class TaskItemList extends TaskItemListAbstract
{
	public TaskItemList()
	{
		super();
	}

	public TaskItemList(int initialSize)
	{
		super(initialSize);
	}

	public TaskItemList(Collection c)
	{
		super(c);
	}

	public TaskItemList(Operation operation)
	{
		super(operation);
	}
}
