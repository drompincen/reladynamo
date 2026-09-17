package com.reladynamo.demo.classifier.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ClassificationResultList extends ClassificationResultListAbstract
{
	public ClassificationResultList()
	{
		super();
	}

	public ClassificationResultList(int initialSize)
	{
		super(initialSize);
	}

	public ClassificationResultList(Collection c)
	{
		super(c);
	}

	public ClassificationResultList(Operation operation)
	{
		super(operation);
	}
}
