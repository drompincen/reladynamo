package com.reladynamo.demo.crm.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class NoteList extends NoteListAbstract
{
	public NoteList()
	{
		super();
	}

	public NoteList(int initialSize)
	{
		super(initialSize);
	}

	public NoteList(Collection c)
	{
		super(c);
	}

	public NoteList(Operation operation)
	{
		super(operation);
	}
}
