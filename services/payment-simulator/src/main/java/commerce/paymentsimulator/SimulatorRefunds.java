package commerce.paymentsimulator;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimulatorRefunds {
    private final JdbcTemplate jdbc;
    public SimulatorRefunds(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public Created create(UUID key, Request request) {
        if (!key.equals(request.refundId())) throw new ProviderProblem(400, "INVALID_IDEMPOTENCY_KEY", "Key must equal refund UUID");
        var payment = jdbc.query("SELECT * FROM provider_payment WHERE id = ? FOR UPDATE",
                (rs, row) -> new Payment(rs.getString("status"), rs.getString("payment_method"),
                        rs.getLong("amount_minor"), rs.getString("currency")), request.paymentId())
                .stream().findFirst().orElseThrow(SimulatorRefunds::notFound);
        var existing = jdbc.query("SELECT * FROM provider_refund WHERE refund_id = ?", (rs, row) -> result(rs), key);
        if (!existing.isEmpty()) {
            var found = existing.getFirst();
            if (!found.paymentId().equals(request.paymentId()) || found.amountMinor() != request.amountMinor()
                    || !found.currency().equals(request.currency())) throw conflict();
            return new Created(found, false, payment.method().equals("pm_refund_lost"));
        }
        if (!payment.status().equals("AUTHORIZED") || payment.amount() != request.amountMinor()
                || !payment.currency().equals(request.currency())) throw conflict();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM provider_refund WHERE payment_id = ? AND status <> 'FAILED')",
                Boolean.class, request.paymentId()))) throw conflict();
        String status = switch (payment.method()) {
            case "pm_refund_declined" -> "FAILED";
            case "pm_refund_unknown" -> "REVIEW_REQUIRED";
            default -> "SUCCEEDED";
        };
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO provider_refund(id, refund_id, payment_id, amount_minor, currency, status) VALUES (?, ?, ?, ?, ?, ?)",
                id, key, request.paymentId(), request.amountMinor(), request.currency(), status);
        jdbc.update("INSERT INTO provider_delivery(id, object_id, event_type) VALUES (?, ?, ?)", UUID.randomUUID(), id, "refund.updated");
        return new Created(new Result(id, key, request.paymentId(), request.amountMinor(), request.currency(), status), true,
                payment.method().equals("pm_refund_lost"));
    }
    @Transactional(readOnly = true)
    public Result get(UUID command) {
        return jdbc.query("SELECT * FROM provider_refund WHERE refund_id = ?", (rs, row) -> result(rs), command)
                .stream().findFirst().orElseThrow(SimulatorRefunds::notFound);
    }
    private static Result result(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Result(rs.getObject("id", UUID.class), rs.getObject("refund_id", UUID.class),
                rs.getObject("payment_id", UUID.class), rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("status"));
    }
    private static ProviderProblem notFound() { return new ProviderProblem(404, "PROVIDER_REFUND_NOT_FOUND", "Provider refund not found"); }
    private static ProviderProblem conflict() { return new ProviderProblem(409, "REFUND_CONFLICT", "Refund conflicts with provider state"); }
    private record Payment(String status, String method, long amount, String currency) { }
    public record Request(@jakarta.validation.constraints.NotNull UUID refundId,
                          @jakarta.validation.constraints.NotNull UUID paymentId,
                          @jakarta.validation.constraints.Positive long amountMinor,
                          @jakarta.validation.constraints.Pattern(regexp = "USD") @jakarta.validation.constraints.NotNull String currency) { }
    public record Result(UUID id, UUID refundId, UUID paymentId, long amountMinor, String currency, String status) { }
    public record Created(Result result, boolean initial, boolean lostResponse) { }
}
