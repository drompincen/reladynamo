package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class VeterinaryVisitList extends VeterinaryVisitListAbstract
{
	public VeterinaryVisitList()
	{
		super();
	}

	public VeterinaryVisitList(int initialSize)
	{
		super(initialSize);
	}

	public VeterinaryVisitList(Collection c)
	{
		super(c);
	}

	public VeterinaryVisitList(Operation operation)
	{
		super(operation);
	}
}
