package commerce.payment;

import commerce.runtime.Event;
import commerce.runtime.Inbox;
import java.util.Set;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class ReservationConsumer {
    private static final Set<String> OTHER_TYPES = Set.of("order.created", "inventory.rejected",
            "payment.authorized", "payment.declined", "order.confirmed", "order.rejected", "order.cancelled", "order.expired", "payment.refunded", "refund.failed");
    private final ObjectMapper mapper;
    private final Inbox inbox;
    private final PaymentStore store;

    public ReservationConsumer(ObjectMapper mapper, Inbox inbox, PaymentStore store) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.store = store;
    }

    @KafkaListener(topics = "commerce.events.v1", groupId = "odexa-payment")
    @Transactional
    public void receive(String raw) {
        Event event;
        try {
            event = mapper.readValue(raw, Event.class);
            if (event == null || event.eventId() == null || event.tenantId() == null
                    || event.occurredAt() == null || event.eventType() == null || event.eventVersion() != 1
                    || event.correlationId() == null
                    || !UUID.fromString(event.correlationId()).toString().equalsIgnoreCase(event.correlationId())) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            // Do not attach parser exceptions: they can contain the raw payment reference.
            throw new IllegalArgumentException("Invalid payment event envelope");
        }
        if (OTHER_TYPES.contains(event.eventType())) return;
        if (!"inventory.reserved".equals(event.eventType())) {
            throw new IllegalArgumentException("Unsupported payment event type");
        }
        ReservedPayment payment = ReservedPayment.parse(event.payload());
        if (inbox.first(event.eventId(), "payment.inventory-reserved.v1")) {
            store.enqueue(event, payment); // Same JDBC transaction as the inbox insertion.
        }
    }
}
