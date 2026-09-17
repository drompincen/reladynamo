package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class CreditRatingList extends CreditRatingListAbstract
{
	public CreditRatingList()
	{
		super();
	}

	public CreditRatingList(int initialSize)
	{
		super(initialSize);
	}

	public CreditRatingList(Collection c)
	{
		super(c);
	}

	public CreditRatingList(Operation operation)
	{
		super(operation);
	}
}
