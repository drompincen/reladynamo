package com.reladynamo.demo.crm.domain;
import java.sql.Timestamp;
public class Payment extends PaymentAbstract
{
	public Payment(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public Payment()
	{
		this(com.reladynamo.demo.crm.util.InfinityTimestamp.getInfinityDate());
	}
}
