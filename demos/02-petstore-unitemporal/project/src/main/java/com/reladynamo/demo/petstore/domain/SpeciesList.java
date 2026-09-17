package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class SpeciesList extends SpeciesListAbstract
{
	public SpeciesList()
	{
		super();
	}

	public SpeciesList(int initialSize)
	{
		super(initialSize);
	}

	public SpeciesList(Collection c)
	{
		super(c);
	}

	public SpeciesList(Operation operation)
	{
		super(operation);
	}
}
