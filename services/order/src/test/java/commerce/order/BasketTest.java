package commerce.order;

import static org.junit.jupiter.api.Assertions.*;
import commerce.runtime.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;

class BasketTest {
    private final UUID a = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final UUID b = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
    private CheckoutRequest request(CheckoutRequest.Item... items) { return new CheckoutRequest(List.of(items), "pm_approved"); }
    @Test void canonicalOrderAndLegacyReplayAndEverySemanticChange() {
        var x = new CheckoutRequest.Item(a, 2); var y = new CheckoutRequest.Item(b, 3);
        assertEquals(request(x,y).fingerprint(), request(y,x).fingerprint());
        assertEquals(new CheckoutRequest(a,2,"pm_approved").fingerprint(), request(x).fingerprint());
        for (var changed : List.of(request(x), request(new CheckoutRequest.Item(a,3),y),
                new CheckoutRequest(List.of(x,y),"pm_declined"), request(x,y,new CheckoutRequest.Item(UUID.randomUUID(),1))))
            assertNotEquals(request(x,y).fingerprint(), changed.fingerprint());
        assertEquals(List.of(x,y),request(y,x).normalizedItems());
    }
    @Test void limitsDuplicatesAndExclusiveForms() {
        var x = new CheckoutRequest.Item(a,1);
        for (var bad : List.of(request(), request(x,x), request(new CheckoutRequest.Item(a,0)),
                request(new CheckoutRequest.Item(a,101)), new CheckoutRequest(a,1,List.of(x),"pm_approved"),
                new CheckoutRequest(null,null,null,"pm_approved"), request(new CheckoutRequest.Item(null,1))))
            assertThrows(ApiException.class,bad::validate);
        var maximum = java.util.stream.IntStream.range(0,20)
                .mapToObj(i -> new CheckoutRequest.Item(UUID.randomUUID(),100)).toList();
        assertEquals(20,new CheckoutRequest(maximum,"pm_approved").normalizedItems().size());
        var tooMany = new ArrayList<>(maximum); tooMany.add(x);
        assertThrows(ApiException.class,() -> new CheckoutRequest(tooMany,"pm_approved").validate());
    }
    @Test void immutableLinesPositiveBasketWithFreeLineAndCheckedTotals() {
        var paid = new OrderLine(a,2,"Paid",2500,5000,7);
        var free = new OrderLine(b,1,"Free",0,0,1);
        var input = new ArrayList<>(List.of(free,paid));
        var snapshot = new CheckoutSnapshot(input,5000,"USD","pm_approved");
        input.clear();
        assertEquals(List.of(paid,free),snapshot.items());
        assertThrows(UnsupportedOperationException.class,() -> snapshot.items().clear());
        assertThrows(IllegalArgumentException.class,() -> new CheckoutSnapshot(List.of(free),0,"USD","pm_approved"));
        assertThrows(ArithmeticException.class,() -> new OrderLine(a,2,"Overflow",Long.MAX_VALUE,0,1));
        assertThrows(ArithmeticException.class,() -> new CheckoutSnapshot(List.of(
                new OrderLine(a,1,"Max",Long.MAX_VALUE,Long.MAX_VALUE,1),paidWithId(b)),1,"USD","pm_approved"));
        assertThrows(IllegalArgumentException.class,() -> new CheckoutSnapshot(List.of(paid,free),4999,"USD","pm_approved"));
    }
    private OrderLine paidWithId(UUID id) { return new OrderLine(id,1,"Paid",1,1,1); }
    @Test void publicViewHasAllLinesAndNeverInventsSingleProduct() {
        var snapshot = new CheckoutSnapshot(List.of(paidWithId(a),paidWithId(b)),2,"USD","pm_approved");
        var order = Order.create(UUID.randomUUID(),UUID.randomUUID(),"owner",snapshot,java.time.Instant.now());
        var view = OrderView.of(order);
        assertNull(view.productId()); assertNull(view.quantity()); assertEquals(2,view.items().size());
        var json = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(view);
        assertFalse(json.has("productId")); assertFalse(json.has("quantity"));
        assertFalse(json.has("paymentMethod")); assertFalse(json.has("customerId"));
    }
}
