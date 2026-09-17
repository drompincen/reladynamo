package com.reladynamo.demo.petstore.domain;
import java.sql.Timestamp;
public class VeterinaryVisit extends VeterinaryVisitAbstract
{
	public VeterinaryVisit(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public VeterinaryVisit()
	{
		this(com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity());
	}
}
