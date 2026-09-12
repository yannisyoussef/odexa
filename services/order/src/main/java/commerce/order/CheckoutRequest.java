package commerce.order;

import commerce.runtime.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public record CheckoutRequest(@NotNull UUID productId, @Min(1) @Max(100) int quantity,
                              @NotNull @Pattern(regexp = "pm_approved|pm_declined") String paymentMethod) {
    public void validate() {
        if (productId == null || quantity < 1 || quantity > 100
                || !("pm_approved".equals(paymentMethod) || "pm_declined".equals(paymentMethod))) {
            throw new ApiException(400, "INVALID_CHECKOUT", "Invalid checkout request");
        }
    }

    public String fingerprint() {
        validate();
        // Canonical parsed values, not raw JSON, so property order and UUID case are irrelevant.
        String canonical = productId + "\n" + quantity + "\n" + paymentMethod;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
