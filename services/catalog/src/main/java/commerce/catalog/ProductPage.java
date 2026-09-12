package commerce.catalog;

import java.util.List;

public record ProductPage(List<Product> items, String nextCursor) {
    public ProductPage {
        items = List.copyOf(items);
    }
}
