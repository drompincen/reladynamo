package com.reladynamo.demo.petstore.domain;
import java.sql.Timestamp;
public class Shipment extends ShipmentAbstract
{
	public Shipment(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public Shipment()
	{
		this(com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity());
	}
}
