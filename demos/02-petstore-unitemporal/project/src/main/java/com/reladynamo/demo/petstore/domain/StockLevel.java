package com.reladynamo.demo.petstore.domain;
import java.sql.Timestamp;
public class StockLevel extends StockLevelAbstract
{
	public StockLevel(Timestamp businessDate
	)
	{
		super(businessDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}
}
