package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class AccountList extends AccountListAbstract
{
	public AccountList()
	{
		super();
	}

	public AccountList(int initialSize)
	{
		super(initialSize);
	}

	public AccountList(Collection c)
	{
		super(c);
	}

	public AccountList(Operation operation)
	{
		super(operation);
	}
}
