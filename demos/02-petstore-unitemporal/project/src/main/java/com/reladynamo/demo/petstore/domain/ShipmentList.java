package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ShipmentList extends ShipmentListAbstract
{
	public ShipmentList()
	{
		super();
	}

	public ShipmentList(int initialSize)
	{
		super(initialSize);
	}

	public ShipmentList(Collection c)
	{
		super(c);
	}

	public ShipmentList(Operation operation)
	{
		super(operation);
	}
}
