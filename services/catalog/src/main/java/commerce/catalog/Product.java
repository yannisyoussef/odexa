package commerce.catalog;

import java.util.UUID;

public record Product(UUID id, String name, String description, long unitPriceMinor,
                      String currency, boolean active, long version) {
}
