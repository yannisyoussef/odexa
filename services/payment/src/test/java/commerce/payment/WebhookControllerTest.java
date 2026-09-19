package commerce.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import commerce.runtime.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class WebhookControllerTest {
    private final ProviderEvents events = mock(ProviderEvents.class);
    private final String secret = "whsec_fixture_only";
    private final WebhookController controller = new WebhookController(events, JsonMapper.builder().build(),
            "old_secret," + secret, "old_secret," + secret);
    private final byte[] body = """
            {"id":"evt_fixture","object":"event","type":"payment_intent.succeeded","livemode":false,
            "data":{"object":{"id":"pi_fixture","object":"payment_intent"}}}
            """.getBytes(StandardCharsets.UTF_8);
    @Test void sdkAndSimulatorVerifyExactRawBytesAndSecretRotation() throws Exception {
        for (String provider : new String[]{"stripe", "simulator"}) {
            var request = request(provider, body, sign(body, Instant.now().getEpochSecond()));
            assertEquals(204, controller.accept(request).getStatusCode().value());
            verify(events).accept(provider, "evt_fixture", "payment_intent.succeeded", "pi_fixture");
        }
    }
    @Test void staleFutureMissingWrongDuplicateSignatureAndModifiedBytesFailBeforeMutation() throws Exception {
        long now = Instant.now().getEpochSecond();
        for (String signature : new String[]{sign(body, now - 301), sign(body, now + 301), "t=1,v1=bad", ""}) {
            for (String provider : new String[]{"stripe", "simulator"}) {
                assertEquals(400, assertThrows(ApiException.class,
                        () -> controller.accept(request(provider, body, signature))).status());
            }
        }
        var duplicate = request("stripe", body, sign(body, now));
        duplicate.addHeader("Stripe-Signature", "second");
        assertThrows(ApiException.class, () -> controller.accept(duplicate));
        byte[] changed = new String(body, StandardCharsets.UTF_8).replace("false", "true").getBytes(StandardCharsets.UTF_8);
        assertThrows(ApiException.class, () -> controller.accept(request("simulator", changed, sign(body, now))));
        verifyNoInteractions(events);
    }
    @Test void validUnknownTypesAreAcceptedButLiveAccountAndMalformedPayloadsAreRejected() throws Exception {
        byte[] unknown = "{\"id\":\"evt_other\",\"type\":\"unknown.new_type\",\"livemode\":false}".getBytes(StandardCharsets.UTF_8);
        assertEquals(204, controller.accept(request("simulator", unknown, sign(unknown, Instant.now().getEpochSecond()))).getStatusCode().value());
        verify(events).accept("simulator", "evt_other", "unknown.new_type", null);
        for (String invalid : new String[]{"{}", "{", "{\"id\":\"evt_x\",\"type\":\"x\",\"livemode\":true}",
                "{\"id\":\"evt_x\",\"type\":\"x\",\"livemode\":false,\"account\":\"acct_other\"}"}) {
            byte[] bytes = invalid.getBytes(StandardCharsets.UTF_8);
            assertThrows(ApiException.class, () -> controller.accept(request("simulator", bytes, sign(bytes, Instant.now().getEpochSecond()))));
        }
        verifyNoMoreInteractions(events);
    }
    @Test void bodyLimitAppliesBeforeSignatureVerification() {
        assertEquals(413, assertThrows(ApiException.class,
                () -> controller.accept(request("stripe", new byte[65537], "invalid"))).status());
        verifyNoInteractions(events);
    }
    private MockHttpServletRequest request(String provider, byte[] bytes, String signature) {
        var request = new MockHttpServletRequest("POST", "/api/v1/webhooks/" + provider);
        request.setContentType("application/json"); request.setContent(bytes);
        request.addHeader(provider.equals("stripe") ? "Stripe-Signature" : "Simulator-Signature", signature);
        return request;
    }
    private String sign(byte[] bytes, long timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(mac.doFinal(bytes));
    }
}
