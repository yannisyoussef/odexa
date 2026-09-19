package commerce.order;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderLifecycleController {
    private final OrderLifecycle lifecycle;
    public OrderLifecycleController(OrderLifecycle lifecycle) { this.lifecycle = lifecycle; }

    @PostMapping("/api/v1/orders/{id}/cancel")
    public OrderView cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) byte[] body, @RequestParam MultiValueMap<String, String> parameters) {
        Actor actor = Actor.from(jwt);
        CheckoutService.authorize(actor);
        if ((body != null && body.length != 0) || !parameters.isEmpty()) {
            throw new ApiException(400, "INVALID_REQUEST", "Cancellation accepts no body or query parameters");
        }
        return OrderView.of(lifecycle.cancel(actor, id));
    }
}
