package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class PaymentList extends PaymentListAbstract
{
	public PaymentList()
	{
		super();
	}

	public PaymentList(int initialSize)
	{
		super(initialSize);
	}

	public PaymentList(Collection c)
	{
		super(c);
	}

	public PaymentList(Operation operation)
	{
		super(operation);
	}
}
