package com.reladynamo.demo.crm.domain;
import java.sql.Timestamp;
public class Customer extends CustomerAbstract
{
	public Customer(Timestamp businessDate
	, Timestamp processingDate
	)
	{
		super(businessDate
		,processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public Customer(Timestamp businessDate)
	{
		super(businessDate);
	}
}
