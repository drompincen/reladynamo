package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class AttachmentList extends AttachmentListAbstract
{
	public AttachmentList()
	{
		super();
	}

	public AttachmentList(int initialSize)
	{
		super(initialSize);
	}

	public AttachmentList(Collection c)
	{
		super(c);
	}

	public AttachmentList(Operation operation)
	{
		super(operation);
	}
}
