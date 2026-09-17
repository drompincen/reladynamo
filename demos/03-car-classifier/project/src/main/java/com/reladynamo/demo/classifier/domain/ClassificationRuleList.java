package com.reladynamo.demo.classifier.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ClassificationRuleList extends ClassificationRuleListAbstract
{
	public ClassificationRuleList()
	{
		super();
	}

	public ClassificationRuleList(int initialSize)
	{
		super(initialSize);
	}

	public ClassificationRuleList(Collection c)
	{
		super(c);
	}

	public ClassificationRuleList(Operation operation)
	{
		super(operation);
	}
}
