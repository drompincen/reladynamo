package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class PipelineStageList extends PipelineStageListAbstract
{
	public PipelineStageList()
	{
		super();
	}

	public PipelineStageList(int initialSize)
	{
		super(initialSize);
	}

	public PipelineStageList(Collection c)
	{
		super(c);
	}

	public PipelineStageList(Operation operation)
	{
		super(operation);
	}
}
