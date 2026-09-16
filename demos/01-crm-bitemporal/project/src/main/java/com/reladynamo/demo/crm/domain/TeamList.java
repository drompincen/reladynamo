package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class TeamList extends TeamListAbstract
{
	public TeamList()
	{
		super();
	}

	public TeamList(int initialSize)
	{
		super(initialSize);
	}

	public TeamList(Collection c)
	{
		super(c);
	}

	public TeamList(Operation operation)
	{
		super(operation);
	}
}
