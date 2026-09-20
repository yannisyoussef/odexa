package commerce.paymentsimulator;

import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/provider/v1/refunds")
public class RefundController {
    private final SimulatorRefunds refunds;
    public RefundController(SimulatorRefunds refunds) { this.refunds = refunds; }
    @PostMapping
    public ResponseEntity<SimulatorRefunds.Result> create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SimulatorRefunds.Request request) {
        UUID id;
        try {
            id = UUID.fromString(key);
            if (!id.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new ProviderProblem(400, "INVALID_IDEMPOTENCY_KEY", "Invalid refund key"); }
        var created = refunds.create(id, request);
        if (created.lostResponse()) throw new ProviderProblem(503, "PROVIDER_UNAVAILABLE", "Provider response unavailable");
        return created.initial() ? ResponseEntity.created(URI.create("/provider/v1/refunds/" + id)).body(created.result())
                : ResponseEntity.ok(created.result());
    }
    @GetMapping("/{refundId}")
    public SimulatorRefunds.Result get(@PathVariable UUID refundId) { return refunds.get(refundId); }
}
