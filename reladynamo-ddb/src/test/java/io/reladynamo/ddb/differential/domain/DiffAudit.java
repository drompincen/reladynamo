package io.reladynamo.ddb.differential.domain;
import java.sql.Timestamp;
public class DiffAudit extends DiffAuditAbstract
{
	public DiffAudit(Timestamp processingDate
	)
	{
		super(processingDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public DiffAudit()
	{
		this(com.gs.fw.common.mithra.util.DefaultInfinityTimestamp.getDefaultInfinity());
	}
}
