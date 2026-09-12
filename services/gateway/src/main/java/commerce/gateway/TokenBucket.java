package commerce.gateway;

import java.util.function.LongSupplier;

/** Global instance-local bucket: constant memory, no attacker-controlled client map. */
final class TokenBucket {
    private final double rate;
    private final int capacity;
    private final LongSupplier clock;
    private double tokens;
    private long last;

    TokenBucket(int rate, int capacity, LongSupplier clock) {
        if (rate < 1 || capacity < 1) {
            throw new IllegalArgumentException("Rate and burst must be positive");
        }
        this.rate = rate;
        this.capacity = capacity;
        this.clock = clock;
        tokens = capacity;
        last = clock.getAsLong();
    }

    synchronized boolean take() {
        long now = clock.getAsLong();
        tokens = Math.min(capacity, tokens + Math.max(0, now - last) / 1_000_000_000.0 * rate);
        last = now;
        if (tokens < 1) {
            return false;
        }
        tokens -= 1;
        return true;
    }
}
