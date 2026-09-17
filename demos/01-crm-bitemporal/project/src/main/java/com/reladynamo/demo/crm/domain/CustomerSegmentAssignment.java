package com.reladynamo.demo.crm.domain;
import java.sql.Timestamp;
public class CustomerSegmentAssignment extends CustomerSegmentAssignmentAbstract
{
	public CustomerSegmentAssignment(Timestamp businessDate
	, Timestamp processingDate
	)
	{
		super(businessDate
		,processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public CustomerSegmentAssignment(Timestamp businessDate)
	{
		super(businessDate);
	}
}
