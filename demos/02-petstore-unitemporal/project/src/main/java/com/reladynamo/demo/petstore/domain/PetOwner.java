package com.reladynamo.demo.petstore.domain;
import java.sql.Timestamp;
public class PetOwner extends PetOwnerAbstract
{
	public PetOwner(Timestamp businessDate
	)
	{
		super(businessDate
		);
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}
}
