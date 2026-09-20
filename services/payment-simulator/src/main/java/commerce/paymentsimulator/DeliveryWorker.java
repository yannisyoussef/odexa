package commerce.paymentsimulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "simulator.webhooks.enabled", havingValue = "true")
public class DeliveryWorker {
    private final DeliveryStore store;
    private final ObjectMapper mapper;
    private final URI target;
    private final String secret;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public DeliveryWorker(DeliveryStore store, ObjectMapper mapper,
            @Value("${simulator.webhooks.url}") String target, @Value("${simulator.webhooks.secret}") String secret) {
        this.store = store; this.mapper = mapper; this.target = URI.create(target); this.secret = secret;
        if (!java.util.Set.of("http", "https").contains(this.target.getScheme()) || this.target.getHost() == null
                || this.target.getUserInfo() != null || this.target.getQuery() != null || this.target.getFragment() != null
                || !this.target.getPath().equals("/api/v1/webhooks/simulator") || secret.isBlank()) {
            throw new IllegalArgumentException("Invalid configured simulator callback");
        }
    }
    @Scheduled(fixedDelayString = "${simulator.webhooks.poll-ms:500}")
    public void runOnce() {
        try { store.claim().ifPresent(this::deliver); }
        catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(DeliveryWorker.class).warn("Provider delivery unavailable; durable retry scheduled");
        }
    }
    private void deliver(DeliveryStore.Delivery delivery) {
        boolean success = false;
        java.util.concurrent.CompletableFuture<?> pending = null;
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of("id", delivery.id().toString(), "type", delivery.type(),
                    "livemode", false, "data", Map.of("object", Map.of("id", delivery.objectId().toString()))));
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("Simulator-Signature", signature(body, secret, Instant.now().getEpochSecond()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            // Status-only response: discard without buffering; total completion is deadline bounded.
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            pending = future;
            int status = future.get(5, TimeUnit.SECONDS).statusCode();
            success = status >= 200 && status < 300;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        catch (Exception failure) { /* Durable retry; never log provider payloads or secrets. */ }
        finally { if (pending != null) pending.cancel(true); }
        store.finish(delivery, success);
    }
    static String signature(byte[] body, String secret, long timestamp) throws java.security.GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
