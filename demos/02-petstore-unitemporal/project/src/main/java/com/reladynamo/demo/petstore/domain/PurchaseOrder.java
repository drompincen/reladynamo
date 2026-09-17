package com.reladynamo.demo.petstore.domain;
import java.sql.Timestamp;
public class PurchaseOrder extends PurchaseOrderAbstract
{
	public PurchaseOrder(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public PurchaseOrder()
	{
		this(com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity());
	}
}
