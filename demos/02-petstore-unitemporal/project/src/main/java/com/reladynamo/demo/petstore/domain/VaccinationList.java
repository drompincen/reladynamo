package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class VaccinationList extends VaccinationListAbstract
{
	public VaccinationList()
	{
		super();
	}

	public VaccinationList(int initialSize)
	{
		super(initialSize);
	}

	public VaccinationList(Collection c)
	{
		super(c);
	}

	public VaccinationList(Operation operation)
	{
		super(operation);
	}
}
