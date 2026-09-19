package commerce.order;

import commerce.runtime.Actor;
import commerce.runtime.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderQueries {
    private final OrderRepository orders;

    public OrderQueries(OrderRepository orders) { this.orders = orders; }

    public OrderPage list(Actor actor, boolean merchant, OrderQuery query) {
        authorize(actor, merchant);
        var found = orders.list(actor.tenantId(), merchant ? null : actor.subject(), query);
        var page = found.stream().limit(query.limit()).toList();
        Order last = page.isEmpty() ? null : page.getLast();
        String cursor = found.size() > query.limit() && last != null
                ? new OrderQuery.Cursor(last.createdAt(), last.id()).encode() : null;
        return new OrderPage(page.stream().map(OrderView::of).toList(), cursor);
    }

    public Order get(Actor actor, boolean merchant, UUID id) {
        authorize(actor, merchant);
        return (merchant ? orders.findTenant(actor.tenantId(), id) : orders.findOwned(actor.tenantId(), actor.subject(), id))
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Order not found"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<OrderHistoryEntry> history(Actor actor, boolean merchant, UUID id) {
        get(actor, merchant, id);
        return orders.history(id);
    }

    static void authorize(Actor actor, boolean merchant) {
        if (merchant) actor.requireRole("MERCHANT_ADMIN", "MERCHANT_USER");
        else CheckoutService.authorize(actor);
    }
}
