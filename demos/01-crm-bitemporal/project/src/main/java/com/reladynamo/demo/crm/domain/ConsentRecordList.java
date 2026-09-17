package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ConsentRecordList extends ConsentRecordListAbstract
{
	public ConsentRecordList()
	{
		super();
	}

	public ConsentRecordList(int initialSize)
	{
		super(initialSize);
	}

	public ConsentRecordList(Collection c)
	{
		super(c);
	}

	public ConsentRecordList(Operation operation)
	{
		super(operation);
	}
}
