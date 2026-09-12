package commerce.payment;

import commerce.runtime.Actor;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentStore store;
    public PaymentController(PaymentStore store) { this.store = store; }

    @GetMapping("/{orderId}")
    public PaymentStore.View get(@PathVariable UUID orderId, @AuthenticationPrincipal Jwt jwt) {
        Actor actor = Actor.from(jwt);
        return store.get(actor.tenantId(), actor.subject(), orderId);
    }
}
