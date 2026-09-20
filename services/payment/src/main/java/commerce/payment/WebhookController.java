package commerce.payment;

import commerce.runtime.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
public class WebhookController {
    static final int MAX_BYTES = 65536;
    private final ProviderEvents events;
    private final ObjectMapper mapper;
    private final String stripeSecrets;
    private final String simulatorSecrets;
    public WebhookController(ProviderEvents events, ObjectMapper mapper,
            @Value("${payment.stripe.webhook-secrets:}") String stripeSecrets,
            @Value("${payment.simulator.webhook-secrets:}") String simulatorSecrets) {
        this.events = events; this.mapper = mapper; this.stripeSecrets = stripeSecrets; this.simulatorSecrets = simulatorSecrets;
    }
    @PostMapping(value = {"/api/v1/webhooks/stripe", "/api/v1/webhooks/simulator"}, consumes = "application/json")
    public ResponseEntity<Void> accept(HttpServletRequest request) throws java.io.IOException {
        if (request.getContentLengthLong() > MAX_BYTES) throw oversized();
        if (request.getHeader("Content-Encoding") != null) throw new ApiException(415, "UNSUPPORTED_ENCODING", "Encoded body not supported");
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) throw oversized();
        boolean stripe = request.getRequestURI().endsWith("/stripe");
        var headers = Collections.list(request.getHeaders(stripe ? "Stripe-Signature" : "Simulator-Signature"));
        if (headers.size() != 1) throw invalid();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            verify(text, body, headers.getFirst(), stripe ? stripeSecrets : simulatorSecrets, stripe);
            var json = mapper.readTree(text);
            if (!json.isObject() || !json.path("livemode").isBoolean() || json.path("livemode").booleanValue()
                    || json.hasNonNull("account") || json.hasNonNull("context")) throw invalid();
            String id = field(json, "id", 200), type = field(json, "type", 100);
            var object = json.path("data").path("object");
            String objectId = object.hasNonNull("id") ? field(object, "id", 200) : null;
            events.accept(stripe ? "stripe" : "simulator", id, type, objectId);
        } catch (ApiException error) { throw error; }
        catch (java.nio.charset.CharacterCodingException | tools.jackson.core.JacksonException error) { throw invalid(); }
        return ResponseEntity.noContent().build();
    }
    static void verify(String text, byte[] body, String header, String secrets, boolean stripe) {
        if (header == null || header.length() > 4096 || secrets.isBlank()) throw invalid();
        try {
            var parts = header.split(",");
            long timestamp = -1;
            for (String part : parts) if (part.startsWith("t=")) {
                if (timestamp != -1) throw invalid();
                timestamp = Long.parseLong(part.substring(2));
            }
            long now = Instant.now().getEpochSecond();
            if (timestamp < now - 300 || timestamp > now + 300) throw invalid();
            for (String secret : secrets.split(",")) {
                if (secret.isBlank()) continue;
                if (stripe) {
                    try { com.stripe.net.Webhook.constructEvent(text, header, secret, 300); return; }
                    catch (com.stripe.exception.SignatureVerificationException | RuntimeException rejected) { continue; }
                }
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
                byte[] expected = mac.doFinal(body);
                for (String part : parts) if (part.matches("v1=[0-9a-fA-F]{64}")
                        && MessageDigest.isEqual(expected, HexFormat.of().parseHex(part.substring(3)))) return;
            }
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw invalid(); }
        throw invalid();
    }
    private static String field(tools.jackson.databind.JsonNode node, String key, int limit) {
        var value = node.get(key);
        if (value == null || !value.isString() || !value.stringValue().matches("[A-Za-z0-9_.-]{1," + limit + "}")) throw invalid();
        return value.stringValue();
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_WEBHOOK", "Webhook verification failed"); }
    private static ApiException oversized() { return new ApiException(413, "REQUEST_TOO_LARGE", "Webhook body exceeds limit"); }
}
