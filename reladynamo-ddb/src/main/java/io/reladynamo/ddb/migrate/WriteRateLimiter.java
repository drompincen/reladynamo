package io.reladynamo.ddb.migrate;

/**
 * Caps backfill writes so a copy cannot consume a table's entire provisioned capacity.
 *
 * <p>One permit per source row written. The first permit is granted immediately; subsequent
 * permits are spaced {@code 1 / writesPerSecond} seconds apart.
 *
 * <p>Java 11 baseline.
 */
public final class WriteRateLimiter {

    private final int writesPerSecond;
    private long nextFreeNanos;

    public WriteRateLimiter(int writesPerSecond) {
        if (writesPerSecond < 1 || writesPerSecond > BackfillConfig.MAX_WRITES_PER_SECOND_CEILING) {
            throw new IllegalArgumentException(
                    "writesPerSecond must be in 1.." + BackfillConfig.MAX_WRITES_PER_SECOND_CEILING
                            + ", not " + writesPerSecond);
        }
        this.writesPerSecond = writesPerSecond;
        this.nextFreeNanos = 0L;
    }

    public void acquire(int permits) {
        if (permits < 0) {
            throw new IllegalArgumentException("permits cannot be negative");
        }
        for (int i = 0; i < permits; i++) {
            acquireOne();
        }
    }

    private void acquireOne() {
        long interval = 1_000_000_000L / writesPerSecond;
        long now = System.nanoTime();
        if (nextFreeNanos > now) {
            long sleepMs = (nextFreeNanos - now + 999_999L) / 1_000_000L;
            if (sleepMs > 0L) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("rate limiter interrupted", e);
                }
            }
            now = System.nanoTime();
        }
        if (nextFreeNanos == 0L) {
            nextFreeNanos = now + interval;
        } else {
            nextFreeNanos = Math.max(now, nextFreeNanos) + interval;
        }
    }
}
