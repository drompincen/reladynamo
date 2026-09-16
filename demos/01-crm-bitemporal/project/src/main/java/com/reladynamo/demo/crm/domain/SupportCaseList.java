package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class SupportCaseList extends SupportCaseListAbstract
{
	public SupportCaseList()
	{
		super();
	}

	public SupportCaseList(int initialSize)
	{
		super(initialSize);
	}

	public SupportCaseList(Collection c)
	{
		super(c);
	}

	public SupportCaseList(Operation operation)
	{
		super(operation);
	}
}
