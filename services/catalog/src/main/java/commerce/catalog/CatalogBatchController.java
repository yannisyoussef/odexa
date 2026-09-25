package commerce.catalog;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
public class CatalogBatchController {
    private final CatalogService service;
    public CatalogBatchController(CatalogService service) { this.service = service; }
    public record Batch(List<UUID> productIds) { }
    public record Snapshot(UUID id, String name, long unitPriceMinor, String currency, boolean active, long version) {
        static Snapshot of(Product p) { return new Snapshot(p.id(),p.name(),p.unitPriceMinor(),p.currency(),p.active(),p.version()); }
    }
    @PostMapping("/api/internal/v1/products/batch")
    public List<Snapshot> batch(@AuthenticationPrincipal Jwt jwt, @RequestBody Batch request) {
        Actor actor = Actor.from(jwt);
        actor.requireRole("CUSTOMER");
        List<UUID> ids = request.productIds();
        if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size())
            throw new ApiException(400, "INVALID_PRODUCT_BATCH", "Expected 1 to 20 distinct product IDs");
        return service.batch(actor.tenantId(), ids).stream().map(Snapshot::of).toList();
    }
}
