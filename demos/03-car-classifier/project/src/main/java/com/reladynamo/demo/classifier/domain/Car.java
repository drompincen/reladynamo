package com.reladynamo.demo.classifier.domain;
public class Car extends CarAbstract
{
	public Car()
	{
		super();
		// You must not modify this constructor. Mithra calls this internally.
		// You can call this constructor. You can also add new constructors.
	}

	public String displayName()
	{
		return getYear() + " " + getMake() + " " + getModel() + " " + getBodyStyle().toLowerCase();
	}
}
