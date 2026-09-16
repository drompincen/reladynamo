package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class CaseEscalationList extends CaseEscalationListAbstract
{
	public CaseEscalationList()
	{
		super();
	}

	public CaseEscalationList(int initialSize)
	{
		super(initialSize);
	}

	public CaseEscalationList(Collection c)
	{
		super(c);
	}

	public CaseEscalationList(Operation operation)
	{
		super(operation);
	}
}
