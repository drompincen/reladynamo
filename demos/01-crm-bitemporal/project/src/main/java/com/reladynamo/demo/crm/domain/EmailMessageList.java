package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class EmailMessageList extends EmailMessageListAbstract
{
	public EmailMessageList()
	{
		super();
	}

	public EmailMessageList(int initialSize)
	{
		super(initialSize);
	}

	public EmailMessageList(Collection c)
	{
		super(c);
	}

	public EmailMessageList(Operation operation)
	{
		super(operation);
	}
}
