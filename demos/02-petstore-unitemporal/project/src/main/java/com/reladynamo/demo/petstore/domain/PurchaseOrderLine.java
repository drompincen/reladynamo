package com.reladynamo.demo.petstore.domain;
import java.sql.Timestamp;
public class PurchaseOrderLine extends PurchaseOrderLineAbstract
{
	public PurchaseOrderLine(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public PurchaseOrderLine()
	{
		this(com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity());
	}
}
