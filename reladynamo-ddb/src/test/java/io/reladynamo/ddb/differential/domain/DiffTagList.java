package io.reladynamo.ddb.differential.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class DiffTagList extends DiffTagListAbstract
{
	public DiffTagList()
	{
		super();
	}

	public DiffTagList(int initialSize)
	{
		super(initialSize);
	}

	public DiffTagList(Collection c)
	{
		super(c);
	}

	public DiffTagList(Operation operation)
	{
		super(operation);
	}
}
