package commerce.inventory;

import commerce.runtime.Actor;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<Stock> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        Stock stock = service.get(Actor.from(jwt).tenantId(), productId);
        return ResponseEntity.ok().eTag("\"" + stock.version() + "\"").body(stock);
    }

    @PutMapping("/{productId}")
    public ResponseEntity<Stock> adjust(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @Valid @RequestBody StockInput input) {
        Actor actor = Actor.from(jwt);
        actor.requireRole("MERCHANT_ADMIN", "MERCHANT_USER");
        Stock stock = service.adjust(actor.tenantId(), productId, InventoryPolicy.expectedVersion(ifMatch), input.onHand());
        return ResponseEntity.ok().eTag("\"" + stock.version() + "\"").body(stock);
    }
}
