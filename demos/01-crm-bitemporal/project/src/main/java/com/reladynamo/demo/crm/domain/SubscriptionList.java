package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class SubscriptionList extends SubscriptionListAbstract
{
	public SubscriptionList()
	{
		super();
	}

	public SubscriptionList(int initialSize)
	{
		super(initialSize);
	}

	public SubscriptionList(Collection c)
	{
		super(c);
	}

	public SubscriptionList(Operation operation)
	{
		super(operation);
	}
}
