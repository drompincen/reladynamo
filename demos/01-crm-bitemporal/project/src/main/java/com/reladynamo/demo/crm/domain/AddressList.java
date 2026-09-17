package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class AddressList extends AddressListAbstract
{
	public AddressList()
	{
		super();
	}

	public AddressList(int initialSize)
	{
		super(initialSize);
	}

	public AddressList(Collection c)
	{
		super(c);
	}

	public AddressList(Operation operation)
	{
		super(operation);
	}
}
