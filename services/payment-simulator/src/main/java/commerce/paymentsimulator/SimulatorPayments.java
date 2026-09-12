package commerce.paymentsimulator;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SimulatorPayments {
    private final JdbcTemplate jdbc;
    public SimulatorPayments(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Created create(UUID key, Request request) {
        if (!request.orderId().equals(key)) {
            throw new ProviderProblem(400, "INVALID_IDEMPOTENCY_KEY", "Key must equal the order UUID");
        }
        String status = decision(request.paymentMethod());
        int inserted = jdbc.update("""
                INSERT INTO provider_payment (id, order_id, amount_minor, currency, payment_method, status)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (order_id) DO NOTHING
                """, UUID.randomUUID(), key, request.amountMinor(), request.currency(), request.paymentMethod(), status);
        // Separate statement under READ COMMITTED sees a concurrently committed winning insertion.
        Stored stored = jdbc.queryForObject("""
                SELECT id, amount_minor, currency, payment_method, status
                FROM provider_payment WHERE order_id = ?
                """, (rs, row) -> new Stored(rs.getObject("id", UUID.class), rs.getLong("amount_minor"),
                rs.getString("currency"), rs.getString("payment_method"), rs.getString("status")), key);
        if (stored == null) throw new IllegalStateException("Provider persistence failed");
        if (stored.amountMinor() != request.amountMinor() || !stored.currency().equals(request.currency())
                || !stored.paymentMethod().equals(request.paymentMethod())) {
            throw new ProviderProblem(409, "IDEMPOTENCY_CONFLICT", "Key was already used for a different payment");
        }
        return new Created(new Result(stored.id(), stored.status()), inserted == 1);
    }

    @Transactional(readOnly = true)
    public Result get(UUID id) {
        return jdbc.query("SELECT id, status FROM provider_payment WHERE id = ?",
                (rs, row) -> new Result(rs.getObject("id", UUID.class), rs.getString("status")), id)
                .stream().findFirst().orElseThrow(() ->
                        new ProviderProblem(404, "PROVIDER_PAYMENT_NOT_FOUND", "Provider payment not found"));
    }

    static String decision(String method) {
        if ("pm_approved".equals(method)) return "AUTHORIZED";
        if ("pm_declined".equals(method)) return "DECLINED";
        throw new ProviderProblem(400, "INVALID_PAYMENT_METHOD", "Unsupported sandbox payment method");
    }

    public record Request(@NotNull UUID orderId, @Positive long amountMinor,
                          @NotNull @Pattern(regexp = "USD") String currency,
                          @NotNull @Pattern(regexp = "pm_approved|pm_declined") String paymentMethod) { }
    public record Result(UUID id, String status) { }
    public record Created(Result result, boolean initial) { }
    private record Stored(UUID id, long amountMinor, String currency, String paymentMethod, String status) { }
}
