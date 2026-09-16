package com.reladynamo.demo.crm.util;

import com.gs.fw.common.mithra.MithraManager;
import com.gs.fw.common.mithra.MithraManagerProvider;

import java.io.InputStream;
import java.util.TimeZone;

public final class ReladomoLifecycle
{
    private ReladomoLifecycle()
    {
    }

    public static void useUtcDefaultTimeZone()
    {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void initializeRuntime(String classpathXml)
    {
        MithraManager manager = MithraManagerProvider.getMithraManager();
        manager.setTransactionTimeout(120);
        InputStream in = ReladomoLifecycle.class.getClassLoader().getResourceAsStream(classpathXml);
        if (in == null)
        {
            throw new IllegalStateException("Missing Reladomo runtime XML: " + classpathXml);
        }
        try
        {
            manager.readConfiguration(in);
        }
        finally
        {
            try
            {
                in.close();
            }
            catch (java.io.IOException ignored)
            {
            }
        }
    }
}
