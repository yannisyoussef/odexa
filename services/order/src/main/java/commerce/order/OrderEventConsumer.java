package commerce.order;

import commerce.runtime.ApiException;
import commerce.runtime.Correlation;
import commerce.runtime.Event;
import commerce.runtime.Inbox;
import commerce.runtime.Outbox;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderEventConsumer {
    static final String CONSUMER = "order-state-v1";
    private final ObjectMapper mapper;
    private final Inbox inbox;
    private final Outbox outbox;
    private final OrderRepository orders;

    public OrderEventConsumer(ObjectMapper mapper, Inbox inbox, Outbox outbox, OrderRepository orders) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.outbox = outbox;
        this.orders = orders;
    }

    @KafkaListener(topics = "commerce.events.v1", groupId = "${spring.kafka.consumer.group-id:order-v1}")
    @Transactional
    public void onMessage(String raw) {
        Event event;
        try {
            event = mapper.readValue(raw, Event.class);
        } catch (RuntimeException malformed) {
            // Do not retain a Jackson exception containing arbitrary raw payload fragments.
            throw new IllegalArgumentException("Invalid commerce event envelope");
        }
        OrderEventDecoder.Decoded decoded = OrderEventDecoder.decode(event);
        if (decoded.ignored()) {
            return;
        }
        String previous = MDC.get(Correlation.MDC_KEY);
        MDC.put(Correlation.MDC_KEY, event.correlationId());
        try {
            if (!inbox.first(event.eventId(), CONSUMER)) {
                return;
            }
            Order before = orders.lock(event.tenantId(), decoded.orderId())
                    .orElseThrow(() -> new ApiException(409, "ORDER_EVENT_NOT_FOUND", "Event order does not exist in tenant"));
            Order after = switch (event.eventType()) {
                case "inventory.reserved" -> {
                    if (!decoded.reservation().matches(before)) {
                        throw new ApiException(409, "ORDER_EVENT_CONFLICT", "Reservation does not match order snapshot");
                    }
                    yield before.reserved(event.eventId());
                }
                case "inventory.rejected" -> before.stockRejected();
                case "payment.authorized", "payment.declined" -> before.payment(new DeferredPayment(
                        event.eventId(), event.causationId(), decoded.paymentId(),
                        "payment.authorized".equals(event.eventType())));
                default -> throw new IllegalArgumentException("Unsupported order event");
            };
            if (!after.equals(before)) {
                orders.save(after);
                if (after.status() == OrderStatus.CONFIRMED && before.status() != OrderStatus.CONFIRMED) {
                    UUID cause = before.deferredPayment() == null ? event.eventId() : before.deferredPayment().eventId();
                    outbox.append("order.confirmed", after.tenantId(), after.id().toString(),
                            new OrderConfirmed(after.id()), cause);
                }
            }
        } finally {
            if (previous == null) {
                MDC.remove(Correlation.MDC_KEY);
            } else {
                MDC.put(Correlation.MDC_KEY, previous);
            }
        }
    }

    public record OrderConfirmed(UUID orderId) { }
}
