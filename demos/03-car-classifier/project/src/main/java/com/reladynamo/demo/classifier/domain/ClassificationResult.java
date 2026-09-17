package com.reladynamo.demo.classifier.domain;
import java.sql.Timestamp;
public class ClassificationResult extends ClassificationResultAbstract
{
	public ClassificationResult(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public ClassificationResult()
	{
		this(com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity());
	}
}
