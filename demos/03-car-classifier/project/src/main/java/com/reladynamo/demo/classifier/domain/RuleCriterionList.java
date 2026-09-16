package com.reladynamo.demo.classifier.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class RuleCriterionList extends RuleCriterionListAbstract
{
	public RuleCriterionList()
	{
		super();
	}

	public RuleCriterionList(int initialSize)
	{
		super(initialSize);
	}

	public RuleCriterionList(Collection c)
	{
		super(c);
	}

	public RuleCriterionList(Operation operation)
	{
		super(operation);
	}
}
