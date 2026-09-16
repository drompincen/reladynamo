package com.reladynamo.demo.crm.domain;
import java.sql.Timestamp;
public class Call extends CallAbstract
{
	public Call(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public Call()
	{
		this(com.reladynamo.demo.crm.util.InfinityTimestamp.getInfinityDate());
	}
}
