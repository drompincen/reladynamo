package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class CallList extends CallListAbstract
{
	public CallList()
	{
		super();
	}

	public CallList(int initialSize)
	{
		super(initialSize);
	}

	public CallList(Collection c)
	{
		super(c);
	}

	public CallList(Operation operation)
	{
		super(operation);
	}
}
