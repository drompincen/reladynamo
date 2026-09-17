package com.reladynamo.demo.petstore.domain;
import com.gs.fw.finder.Operation;
import java.util.*;
public class ProductCategoryList extends ProductCategoryListAbstract
{
	public ProductCategoryList()
	{
		super();
	}

	public ProductCategoryList(int initialSize)
	{
		super(initialSize);
	}

	public ProductCategoryList(Collection c)
	{
		super(c);
	}

	public ProductCategoryList(Operation operation)
	{
		super(operation);
	}
}
