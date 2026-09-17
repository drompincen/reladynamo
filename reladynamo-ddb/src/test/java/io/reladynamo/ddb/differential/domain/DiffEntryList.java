package io.reladynamo.ddb.differential.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class DiffEntryList extends DiffEntryListAbstract
{
	public DiffEntryList()
	{
		super();
	}

	public DiffEntryList(int initialSize)
	{
		super(initialSize);
	}

	public DiffEntryList(Collection c)
	{
		super(c);
	}

	public DiffEntryList(Operation operation)
	{
		super(operation);
	}
}
