package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ContactList extends ContactListAbstract
{
	public ContactList()
	{
		super();
	}

	public ContactList(int initialSize)
	{
		super(initialSize);
	}

	public ContactList(Collection c)
	{
		super(c);
	}

	public ContactList(Operation operation)
	{
		super(operation);
	}
}
