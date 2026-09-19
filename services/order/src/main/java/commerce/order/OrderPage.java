package commerce.order;

import java.util.List;

public record OrderPage(List<OrderView> items, String nextCursor) { }
