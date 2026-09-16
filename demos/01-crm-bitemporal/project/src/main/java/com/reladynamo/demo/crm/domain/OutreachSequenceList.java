package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class OutreachSequenceList extends OutreachSequenceListAbstract
{
	public OutreachSequenceList()
	{
		super();
	}

	public OutreachSequenceList(int initialSize)
	{
		super(initialSize);
	}

	public OutreachSequenceList(Collection c)
	{
		super(c);
	}

	public OutreachSequenceList(Operation operation)
	{
		super(operation);
	}
}
