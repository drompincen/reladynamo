package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ContractList extends ContractListAbstract
{
	public ContractList()
	{
		super();
	}

	public ContractList(int initialSize)
	{
		super(initialSize);
	}

	public ContractList(Collection c)
	{
		super(c);
	}

	public ContractList(Operation operation)
	{
		super(operation);
	}
}
