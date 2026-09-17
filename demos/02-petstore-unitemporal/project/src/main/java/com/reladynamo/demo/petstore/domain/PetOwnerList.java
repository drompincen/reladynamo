package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class PetOwnerList extends PetOwnerListAbstract
{
	public PetOwnerList()
	{
		super();
	}

	public PetOwnerList(int initialSize)
	{
		super(initialSize);
	}

	public PetOwnerList(Collection c)
	{
		super(c);
	}

	public PetOwnerList(Operation operation)
	{
		super(operation);
	}
}
