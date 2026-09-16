package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class BreedList extends BreedListAbstract
{
	public BreedList()
	{
		super();
	}

	public BreedList(int initialSize)
	{
		super(initialSize);
	}

	public BreedList(Collection c)
	{
		super(c);
	}

	public BreedList(Operation operation)
	{
		super(operation);
	}
}
