package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class MessageTemplateList extends MessageTemplateListAbstract
{
	public MessageTemplateList()
	{
		super();
	}

	public MessageTemplateList(int initialSize)
	{
		super(initialSize);
	}

	public MessageTemplateList(Collection c)
	{
		super(c);
	}

	public MessageTemplateList(Operation operation)
	{
		super(operation);
	}
}
