package commerce.order;

import commerce.runtime.Actor;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final CheckoutService service;

    public OrderController(CheckoutService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderView> checkout(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CheckoutRequest request) {
        CheckoutWriter.Result result = service.checkout(Actor.from(jwt), jwt.getTokenValue(), key, request);
        return ResponseEntity.status(result.created() ? 201 : 200)
                .location(URI.create("/api/v1/orders/" + result.order().id()))
                .body(OrderView.of(result.order()));
    }

    @GetMapping("/{id}")
    public OrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return OrderView.of(service.get(Actor.from(jwt), id));
    }
}
