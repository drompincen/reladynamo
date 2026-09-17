package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class OpportunityList extends OpportunityListAbstract
{
	public OpportunityList()
	{
		super();
	}

	public OpportunityList(int initialSize)
	{
		super(initialSize);
	}

	public OpportunityList(Collection c)
	{
		super(c);
	}

	public OpportunityList(Operation operation)
	{
		super(operation);
	}
}
