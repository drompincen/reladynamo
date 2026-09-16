package io.reladynamo.ddb.differential.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class DiffPositionList extends DiffPositionListAbstract
{
	public DiffPositionList()
	{
		super();
	}

	public DiffPositionList(int initialSize)
	{
		super(initialSize);
	}

	public DiffPositionList(Collection c)
	{
		super(c);
	}

	public DiffPositionList(Operation operation)
	{
		super(operation);
	}
}
