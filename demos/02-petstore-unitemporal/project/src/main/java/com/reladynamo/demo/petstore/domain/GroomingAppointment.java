package com.reladynamo.demo.petstore.domain;
import java.sql.Timestamp;
public class GroomingAppointment extends GroomingAppointmentAbstract
{
	public GroomingAppointment(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public GroomingAppointment()
	{
		this(com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity());
	}
}
