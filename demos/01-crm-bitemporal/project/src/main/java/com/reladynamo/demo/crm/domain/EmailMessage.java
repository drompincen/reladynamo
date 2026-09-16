package com.reladynamo.demo.crm.domain;
import java.sql.Timestamp;
public class EmailMessage extends EmailMessageAbstract
{
	public EmailMessage(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public EmailMessage()
	{
		this(com.reladynamo.demo.crm.util.InfinityTimestamp.getInfinityDate());
	}
}
