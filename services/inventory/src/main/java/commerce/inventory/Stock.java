package commerce.inventory;

import java.util.UUID;

public record Stock(UUID productId, long onHand, long reserved, long available, long version) {
}
