package commerce.catalog;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProductInput(
        @NotBlank @Size(max = 200) String name,
        @NotNull @Size(max = 2000) String description,
        @NotNull @Min(0) Long unitPriceMinor,
        @NotNull @Pattern(regexp = "USD") String currency,
        @NotNull Boolean active) {
}
