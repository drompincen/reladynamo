package com.reladynamo.demo.petstore.runtime;

import com.gs.fw.common.mithra.MithraManagerProvider;
import java.io.IOException;
import java.io.InputStream;

/**
 * Loads the Reladomo runtime against the H2 connection manager and applies schema DDL.
 */
public final class PetstoreBootstrap {

    private static volatile boolean started;

    private PetstoreBootstrap() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        MithraManagerProvider.getMithraManager().setTransactionTimeout(120);
        H2PetstoreConnectionManager.getInstance().ensureSchema();
        try (InputStream in = PetstoreBootstrap.class.getResourceAsStream("/reladomo/MithraRuntime.xml")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource /reladomo/MithraRuntime.xml");
            }
            MithraManagerProvider.getMithraManager().readConfiguration(in);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load MithraRuntime.xml", e);
        }
        started = true;
    }
}
