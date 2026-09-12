package commerce.catalog;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class CatalogController {
    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping
    public ProductPage list(@AuthenticationPrincipal Jwt jwt,
                            @RequestParam(defaultValue = "20") int limit,
                            @RequestParam(required = false) String cursor,
                            @RequestParam(required = false, name = "q") String query) {
        return service.list(Actor.from(jwt).tenantId(), limit, cursor, query);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Product product = service.get(Actor.from(jwt).tenantId(), id);
        return ResponseEntity.ok().eTag("\"" + product.version() + "\"").body(product);
    }

    @PostMapping
    public ResponseEntity<Product> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProductInput input) {
        Actor actor = merchant(jwt);
        Product product = service.create(actor.tenantId(), input);
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id()))
                .eTag("\"" + product.version() + "\"").body(product);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                         @Valid @RequestBody ProductInput input) {
        Actor actor = merchant(jwt);
        Product product = service.update(actor.tenantId(), id, CatalogPolicy.expectedVersion(ifMatch), input);
        return ResponseEntity.ok().eTag("\"" + product.version() + "\"").body(product);
    }

    private static Actor merchant(Jwt jwt) {
        Actor actor = Actor.from(jwt);
        actor.requireRole("MERCHANT_ADMIN", "MERCHANT_USER");
        return actor;
    }
}
