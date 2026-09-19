package commerce.payment;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class RefundController {
    private final RefundStore store;
    public RefundController(RefundStore store) { this.store = store; }
    @PostMapping("/api/v1/merchant/payments/{orderId}/refunds")
    public ResponseEntity<RefundStore.View> create(@PathVariable UUID orderId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Request request,
            @AuthenticationPrincipal Jwt jwt) {
        var created = store.create(Actor.from(jwt), orderId, key, request.amountMinor(), request.currency());
        return created.initial() ? ResponseEntity.created(URI.create("/api/v1/merchant/payments/" + orderId
                + "/refunds/" + created.view().id())).body(created.view()) : ResponseEntity.ok(created.view());
    }
    @GetMapping({"/api/v1/payments/{orderId}/refunds", "/api/v1/merchant/payments/{orderId}/refunds"})
    public RefundStore.Page list(@PathVariable UUID orderId, @RequestParam(required = false) UUID cursor,
            @AuthenticationPrincipal Jwt jwt, jakarta.servlet.http.HttpServletRequest request) {
        if (request.getParameterMap().keySet().stream().anyMatch(k -> !k.equals("cursor"))
                || request.getParameterValues("cursor") != null && request.getParameterValues("cursor").length != 1) {
            throw new ApiException(400, "INVALID_QUERY", "Invalid refund query");
        }
        return store.list(Actor.from(jwt), orderId, merchant(request), cursor);
    }
    @GetMapping({"/api/v1/payments/{orderId}/refunds/{refundId}",
            "/api/v1/merchant/payments/{orderId}/refunds/{refundId}"})
    public RefundStore.View get(@PathVariable UUID orderId, @PathVariable UUID refundId,
            @AuthenticationPrincipal Jwt jwt, jakarta.servlet.http.HttpServletRequest request) {
        return store.get(Actor.from(jwt), orderId, refundId, merchant(request));
    }
    private boolean merchant(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/merchant/");
    }
    public record Request(@Positive long amountMinor, @NotNull @Pattern(regexp = "USD") String currency) { }
}
