package com.reladynamo.demo.crm;

import com.reladynamo.demo.crm.demo.Demonstrations;
import com.reladynamo.demo.crm.seed.SeedData;
import com.reladynamo.demo.crm.util.H2ConnectionManager;
import com.reladynamo.demo.crm.util.ReladomoLifecycle;

/**
 * Standalone Reladomo + H2 bitemporal CRM demo.
 *
 * <p>Run with {@code mvn -q exec:java}.
 */
public final class CrmBitemporalDemo
{
    private CrmBitemporalDemo()
    {
    }

    public static void main(String[] args)
    {
        ReladomoLifecycle.useUtcDefaultTimeZone();
        H2ConnectionManager.getInstance().prepareTables();
        ReladomoLifecycle.initializeRuntime("reladomo/ReladomoRuntimeConfig.xml");
        int records = SeedData.load();
        System.out.println("crm-bitemporal-demo");
        System.out.println("Seeded " + records + " logical records (deterministic timestamps, UTC).");
        Demonstrations.printAll(System.out);
        H2ConnectionManager.getInstance().shutdown();
    }
}
