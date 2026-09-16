package com.reladynamo.demo.classifier.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ResultLabelList extends ResultLabelListAbstract
{
	public ResultLabelList()
	{
		super();
	}

	public ResultLabelList(int initialSize)
	{
		super(initialSize);
	}

	public ResultLabelList(Collection c)
	{
		super(c);
	}

	public ResultLabelList(Operation operation)
	{
		super(operation);
	}
}
