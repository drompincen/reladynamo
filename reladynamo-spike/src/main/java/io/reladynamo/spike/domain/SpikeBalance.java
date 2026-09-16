package io.reladynamo.spike.domain;
import java.sql.Timestamp;
public class SpikeBalance extends SpikeBalanceAbstract
{
	public SpikeBalance(Timestamp businessDate
	, Timestamp processingDate
	)
	{
		super(businessDate
		,processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public SpikeBalance(Timestamp businessDate)
	{
		super(businessDate);
	}
}
