package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class GroomingAppointmentList extends GroomingAppointmentListAbstract
{
	public GroomingAppointmentList()
	{
		super();
	}

	public GroomingAppointmentList(int initialSize)
	{
		super(initialSize);
	}

	public GroomingAppointmentList(Collection c)
	{
		super(c);
	}

	public GroomingAppointmentList(Operation operation)
	{
		super(operation);
	}
}
