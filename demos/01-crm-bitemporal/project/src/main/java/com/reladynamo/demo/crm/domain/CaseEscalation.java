package com.reladynamo.demo.crm.domain;
import java.sql.Timestamp;
public class CaseEscalation extends CaseEscalationAbstract
{
	public CaseEscalation(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public CaseEscalation()
	{
		this(com.reladynamo.demo.crm.util.InfinityTimestamp.getInfinityDate());
	}
}
