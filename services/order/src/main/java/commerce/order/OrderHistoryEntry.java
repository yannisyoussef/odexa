package commerce.order;

import java.time.Instant;

/** Local state-transition time; legacy snapshots are explicitly labelled observations. */
public record OrderHistoryEntry(long version, OrderStatus status, Instant occurredAt, String reason) { }
