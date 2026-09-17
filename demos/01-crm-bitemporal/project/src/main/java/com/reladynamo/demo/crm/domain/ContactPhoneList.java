package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ContactPhoneList extends ContactPhoneListAbstract
{
	public ContactPhoneList()
	{
		super();
	}

	public ContactPhoneList(int initialSize)
	{
		super(initialSize);
	}

	public ContactPhoneList(Collection c)
	{
		super(c);
	}

	public ContactPhoneList(Operation operation)
	{
		super(operation);
	}
}
