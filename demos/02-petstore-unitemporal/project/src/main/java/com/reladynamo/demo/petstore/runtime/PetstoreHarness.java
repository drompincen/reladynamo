package com.reladynamo.demo.petstore.runtime;

import com.reladynamo.demo.petstore.demo.Demonstrations;
import com.reladynamo.demo.petstore.seed.PetstoreSeed;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot startup used by both {@code main()} and the JUnit tests: UTC, Reladomo runtime, H2
 * schema, deterministic seed, then the six demonstrations.
 */
public final class PetstoreHarness {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private PetstoreHarness() {
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        PetstoreBootstrap.start();
        PetstoreSeed.populate();
        Demonstrations.apply();
    }
}
