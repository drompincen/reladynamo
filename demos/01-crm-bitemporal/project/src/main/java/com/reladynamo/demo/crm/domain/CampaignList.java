package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class CampaignList extends CampaignListAbstract
{
	public CampaignList()
	{
		super();
	}

	public CampaignList(int initialSize)
	{
		super(initialSize);
	}

	public CampaignList(Collection c)
	{
		super(c);
	}

	public CampaignList(Operation operation)
	{
		super(operation);
	}
}
