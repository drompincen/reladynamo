package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class CompanyList extends CompanyListAbstract
{
	public CompanyList()
	{
		super();
	}

	public CompanyList(int initialSize)
	{
		super(initialSize);
	}

	public CompanyList(Collection c)
	{
		super(c);
	}

	public CompanyList(Operation operation)
	{
		super(operation);
	}
}
