package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class CampaignMemberList extends CampaignMemberListAbstract
{
	public CampaignMemberList()
	{
		super();
	}

	public CampaignMemberList(int initialSize)
	{
		super(initialSize);
	}

	public CampaignMemberList(Collection c)
	{
		super(c);
	}

	public CampaignMemberList(Operation operation)
	{
		super(operation);
	}
}
