package com.reladynamo.demo.crm;

import com.gs.fw.common.mithra.databasetype.H2DatabaseType;
import com.gs.fw.common.mithra.test.ConnectionManagerForTests;
import com.gs.fw.common.mithra.test.MithraTestResource;
import com.reladynamo.demo.crm.seed.SeedData;
import com.reladynamo.demo.crm.util.ReladomoLifecycle;

import java.util.TimeZone;

/**
 * Wires {@link MithraTestResource} to an in-memory H2 database and loads the deterministic seed.
 */
public final class ReladomoTestHarness
{
    private MithraTestResource mithraTestResource;

    public void setUp()
    {
        ReladomoLifecycle.useUtcDefaultTimeZone();
        mithraTestResource = new MithraTestResource("reladomo/TestReladomoRuntimeConfig.xml");
        ConnectionManagerForTests connectionManager = ConnectionManagerForTests.getInstance("crm");
        connectionManager.setDefaultSource("crm");
        connectionManager.setDatabaseTimeZone(TimeZone.getTimeZone("UTC"));
        connectionManager.setDatabaseType(H2DatabaseType.getInstance());
        mithraTestResource.createSingleDatabase(connectionManager);
        mithraTestResource.setUp();
        SeedData.load();
    }

    public void tearDown()
    {
        if (mithraTestResource != null)
        {
            mithraTestResource.tearDown();
            mithraTestResource = null;
        }
    }
}
