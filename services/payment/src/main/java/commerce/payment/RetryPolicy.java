package commerce.payment;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class RetryPolicy {
    private final int maxAttempts;
    private final int leaseSeconds;
    private final int baseSeconds;
    private final int maxSeconds;

    public RetryPolicy(@Value("${payment.worker.max-attempts:5}") int maxAttempts,
            @Value("${payment.worker.lease-seconds:30}") int leaseSeconds,
            @Value("${payment.worker.base-backoff-seconds:2}") int baseSeconds,
            @Value("${payment.worker.max-backoff-seconds:60}") int maxSeconds) {
        if (maxAttempts < 1 || maxAttempts > 20 || leaseSeconds < 10 || leaseSeconds > 600
                || baseSeconds < 1 || maxSeconds < baseSeconds || maxSeconds > 3600) {
            throw new IllegalArgumentException("Invalid bounded payment retry configuration");
        }
        this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds;
        this.baseSeconds = baseSeconds;
        this.maxSeconds = maxSeconds;
    }

    public int maxAttempts() { return maxAttempts; }
    public int leaseSeconds() { return leaseSeconds; }
    public boolean exhausted(int attempts) { return attempts >= maxAttempts; }
    public Duration delay(int attempts) {
        int exponent = Math.clamp(attempts - 1, 0, 20);
        return Duration.ofSeconds(Math.min(maxSeconds, (long) baseSeconds << exponent));
    }
}
