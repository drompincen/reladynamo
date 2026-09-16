package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ContactEmailList extends ContactEmailListAbstract
{
	public ContactEmailList()
	{
		super();
	}

	public ContactEmailList(int initialSize)
	{
		super(initialSize);
	}

	public ContactEmailList(Collection c)
	{
		super(c);
	}

	public ContactEmailList(Operation operation)
	{
		super(operation);
	}
}
