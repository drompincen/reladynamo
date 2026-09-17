package com.reladynamo.demo.classifier.domain;
import java.sql.Timestamp;
public class ClassificationRule extends ClassificationRuleAbstract
{
	public ClassificationRule(Timestamp businessDate
	, Timestamp processingDate
	)
	{
		super(businessDate
		,processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public ClassificationRule(Timestamp businessDate)
	{
		super(businessDate);
	}
}
