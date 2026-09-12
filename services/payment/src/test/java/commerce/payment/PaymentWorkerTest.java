package commerce.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PaymentWorkerTest {
    private final PaymentStore store = mock(PaymentStore.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final PaymentWorker worker = new PaymentWorker(store, provider);
    private final PaymentStore.Claim claim = new PaymentStore.Claim(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), 2500, "USD", "pm_approved", 1, UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID().toString());

    @Test void authoritativeResultIsPersistedWithOriginalOrderKeyAndCorrelation() {
        when(store.claim()).thenReturn(Optional.of(claim));
        var result = new PaymentProvider.Result(UUID.randomUUID().toString(), PaymentProvider.Outcome.AUTHORIZED);
        when(provider.authorize(claim.request())).thenAnswer(invocation -> {
            assertEquals(claim.correlationId(), MDC.get("correlationId"));
            return result;
        });
        worker.runOnce();
        var sequence = inOrder(store, provider);
        sequence.verify(store).claim();
        sequence.verify(provider).authorize(claim.request());
        sequence.verify(store).complete(claim, result);
        verify(store, never()).uncertain(any());
        assertNull(MDC.get("correlationId"));
    }

    @Test void timeoutAndUnexpectedProviderFailureNeverBecomeDeclines() {
        when(store.claim()).thenReturn(Optional.of(claim));
        when(provider.authorize(any())).thenThrow(new PaymentProvider.UncertainOutcome());
        worker.runOnce();
        verify(store).uncertain(claim);
        verify(store, never()).complete(any(), any());
    }

    @Test void databaseFailureAfterProviderSuccessIsRecoveredByLeaseNotReclassified() {
        when(store.claim()).thenReturn(Optional.of(claim));
        var result = new PaymentProvider.Result("provider-result", PaymentProvider.Outcome.AUTHORIZED);
        when(provider.authorize(any())).thenReturn(result);
        when(store.complete(claim, result)).thenThrow(new IllegalStateException());
        assertDoesNotThrow(worker::runOnce);
        verify(store, never()).uncertain(any());
    }

    @Test void retryPolicyIsBoundedExponentialAndRejectsUnsafeConfiguration() {
        var policy = new RetryPolicy(5, 30, 2, 60);
        assertEquals(Duration.ofSeconds(2), policy.delay(1));
        assertEquals(Duration.ofSeconds(4), policy.delay(2));
        assertEquals(Duration.ofSeconds(60), policy.delay(20));
        assertFalse(policy.exhausted(4));
        assertTrue(policy.exhausted(5));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 30, 2, 60));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(5, 1, 2, 60));
    }
}
