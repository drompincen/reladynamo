package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class LeadSourceList extends LeadSourceListAbstract
{
	public LeadSourceList()
	{
		super();
	}

	public LeadSourceList(int initialSize)
	{
		super(initialSize);
	}

	public LeadSourceList(Collection c)
	{
		super(c);
	}

	public LeadSourceList(Operation operation)
	{
		super(operation);
	}
}
