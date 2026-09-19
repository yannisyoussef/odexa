package commerce.order;

import commerce.runtime.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderQueryController {
    private final OrderQueries queries;
    public OrderQueryController(OrderQueries queries) { this.queries = queries; }

    @GetMapping("/api/v1/orders")
    public OrderPage customerList(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> parameters) {
        return list(jwt, false, parameters);
    }

    @GetMapping("/api/v1/merchant/orders")
    public OrderPage merchantList(@AuthenticationPrincipal Jwt jwt, @RequestParam MultiValueMap<String, String> parameters) {
        return list(jwt, true, parameters);
    }

    @GetMapping("/api/v1/merchant/orders/{id}")
    public OrderView merchantGet(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return OrderView.of(queries.get(Actor.from(jwt), true, id));
    }

    @GetMapping("/api/v1/orders/{id}/history")
    public List<OrderHistoryEntry> customerHistory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return queries.history(Actor.from(jwt), false, id);
    }

    @GetMapping("/api/v1/merchant/orders/{id}/history")
    public List<OrderHistoryEntry> merchantHistory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return queries.history(Actor.from(jwt), true, id);
    }

    private OrderPage list(Jwt jwt, boolean merchant, MultiValueMap<String, String> parameters) {
        Actor actor = Actor.from(jwt);
        OrderQueries.authorize(actor, merchant);
        return queries.list(actor, merchant, OrderQuery.parse(parameters));
    }
}
