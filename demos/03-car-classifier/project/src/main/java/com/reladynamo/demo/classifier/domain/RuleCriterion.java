package com.reladynamo.demo.classifier.domain;
import java.sql.Timestamp;
public class RuleCriterion extends RuleCriterionAbstract
{
	public RuleCriterion(Timestamp businessDate
	, Timestamp processingDate
	)
	{
		super(businessDate
		,processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public RuleCriterion(Timestamp businessDate)
	{
		super(businessDate);
	}
}
