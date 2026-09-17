package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class AdoptionList extends AdoptionListAbstract
{
	public AdoptionList()
	{
		super();
	}

	public AdoptionList(int initialSize)
	{
		super(initialSize);
	}

	public AdoptionList(Collection c)
	{
		super(c);
	}

	public AdoptionList(Operation operation)
	{
		super(operation);
	}
}
