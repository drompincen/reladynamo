package io.reladynamo.ddb.differential.domain;
import java.sql.Timestamp;
public class DiffBalance extends DiffBalanceAbstract
{
	public DiffBalance(Timestamp businessDate
	, Timestamp processingDate
	)
	{
		super(businessDate
		,processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public DiffBalance(Timestamp businessDate)
	{
		super(businessDate);
	}
}
