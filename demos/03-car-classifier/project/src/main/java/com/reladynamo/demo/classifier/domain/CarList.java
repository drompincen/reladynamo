package com.reladynamo.demo.classifier.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class CarList extends CarListAbstract
{
	public CarList()
	{
		super();
	}

	public CarList(int initialSize)
	{
		super(initialSize);
	}

	public CarList(Collection c)
	{
		super(c);
	}

	public CarList(Operation operation)
	{
		super(operation);
	}
}
