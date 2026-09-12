package commerce.inventory;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockInput(@NotNull @Min(0) Long onHand) {
}
