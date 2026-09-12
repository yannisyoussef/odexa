package commerce.paymentsimulator;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/provider/v1/payments")
public class ProviderController {
    private final SimulatorPayments payments;
    public ProviderController(SimulatorPayments payments) { this.payments = payments; }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<SimulatorPayments.Result> create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SimulatorPayments.Request request) {
        UUID parsed;
        try {
            parsed = UUID.fromString(key);
            if (!parsed.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new ProviderProblem(400, "INVALID_IDEMPOTENCY_KEY", "Key must be an order UUID");
        }
        SimulatorPayments.Created created = payments.create(parsed, request);
        return created.initial()
                ? ResponseEntity.created(URI.create("/provider/v1/payments/" + created.result().id())).body(created.result())
                : ResponseEntity.ok(created.result());
    }

    @GetMapping(value = "/{id}", produces = "application/json")
    public SimulatorPayments.Result get(@PathVariable UUID id) { return payments.get(id); }
}
