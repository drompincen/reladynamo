package io.reladynamo.ddb.differential.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class DiffAuditList extends DiffAuditListAbstract
{
	public DiffAuditList()
	{
		super();
	}

	public DiffAuditList(int initialSize)
	{
		super(initialSize);
	}

	public DiffAuditList(Collection c)
	{
		super(c);
	}

	public DiffAuditList(Operation operation)
	{
		super(operation);
	}
}
