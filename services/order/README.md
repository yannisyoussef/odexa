# Order service

Single-product checkout at `/api/v1/orders`, default port 8083. Requires the shared runtime module, a private PostgreSQL database, Kafka and the tenant-authorized catalog. The public contract is `contracts/openapi/order.json`.

## Checkout boundary

- JWT `tenant_id` and `sub` are the only tenant/customer authority. Customer endpoints require `CUSTOMER`; other roles do not bypass ownership. Explicit merchant paths require `MERCHANT_ADMIN` or `MERCHANT_USER` within the signed tenant. Reads filter tenant and owner together and return 404 for all absent/inaccessible IDs.
- The idempotency identity is `(tenant_id, customer_id, idempotency_key)`. The SHA-256 fingerprint encodes canonical product UUID, quantity and provider reference. Records and keys have no automatic expiry.
- A persisted matching request is returned before contacting catalog. A changed fingerprint returns 409 before contacting catalog. After a catalog failure, a second lookup can recover a concurrently committed matching request.
- New requests forward their bearer JWT and correlation UUID to catalog. Redirects are disabled, default connection/read timeouts are 2s/3s, and configuration rejects nonpositive or greater-than-10s timeouts. Only an active, matching product with valid USD price/version is accepted; totals use checked multiplication.
- Network access runs outside the database transaction. `CheckoutWriter` uses READ_COMMITTED and `INSERT ... ON CONFLICT DO NOTHING`, then reads the committed winner. It never catches a unique violation in an aborted transaction. Only the inserted winner appends `order.created`, in the same transaction.
- A new order returns 201; retry returns 200, the same Location and the current public order state. Snapshot timestamp precision is normalized by reading the persisted row before returning.

## State machine and causality

| State | inventory.reserved | inventory.rejected | payment.authorized / payment.declined |
| --- | --- | --- | --- |
| CREATED | PENDING_PAYMENT; apply matching deferred result if any | STOCK_REJECTED, unless conflicting payment evidence exists | Persist deferred evidence; remain CREATED |
| PENDING_PAYMENT | Same event is inert; another reservation conflicts | Conflict | CONFIRMED / PAYMENT_FAILED only if causation matches reservation event ID |
| Any terminal state | Inert | Inert | Inert, including contradictory late results |

The aggregate is immutable. A validated reservation payload must match the order's customer, product, quantity, total, currency and payment method. A payment must have a payment UUID and non-null reservation causation UUID.

A payment producer can publish before the order consumer receives the causative inventory event, despite using the same order Kafka key. The consumer therefore saves the early result's event ID, reservation event ID, payment ID and outcome durably on the order without changing its public state. When that exact reservation event arrives, both state transitions are applied atomically. `order.confirmed` uses the payment event ID as causation, including this deferred path. Valid events never require unsafe CREATED-to-terminal shortcuts.

The listener's inbox insert, row lock, state/evidence mutation and history and lifecycle outbox appends share one database transaction. Duplicates are inert. Unknown event types/versions, malformed owned payloads, missing tenant orders, or conflicting preterminal evidence throw sanitized errors for runtime bounded recovery/DLT; their inbox inserts roll back. A terminal order never reopens or changes outcome. Runtime event access must be restricted to trusted service producers; the envelope is not a public callback authentication mechanism.

Deferred results have no timeout-based fallback: if their reservation event never arrives, the order remains CREATED pending delivery/operator recovery. Indeterminate payment is not converted to a decline and reserved stock is never locally released by this service.

## Tests and validation

- `OrderStateMachineTest`: all state/signal pairs, deferred causality, contradictory outcomes and immutability.
- `CheckoutServiceTest`: retry without catalog, fingerprint conflicts, concurrent winner recovery, bounds and ownership.
- `CatalogClientTest`: forwarded headers, authoritative snapshots, overflow, invalid/inactive products and transport timeout mapping.
- `OrderEventConsumerTest`: parsing, dedup, deferred confirmation, snapshot validation and MDC restoration.
- `OrderHttpTest`: real runtime security chain with prevalidated synthetic JWT principals, ownership, response status/Location and request validation. JWT signature/issuer/audience verification belongs to runtime tests.
- `OrderDatabaseIntegrationTest`: real PostgreSQL Testcontainers tests for concurrent same/different intent, tenant/customer scope, outbox rollback, inbox rollback, deferred outcomes and tenant-isolated events. Tagged `integration`, with `@Testcontainers(disabledWithoutDocker = true)`. The tests use a dedicated disposable container and do not start Kafka or a full application.

Run from the repository root with Java 25 using `:services:order:test`, `:services:order:integrationTest` and `:services:order:bootJar`. The root build excludes integration-tagged tests from the normal test task; CI must require a Docker preflight before the integration task so skipped containers cannot be mistaken for validation.


## Queries and lifecycle operations

`GET /api/v1/orders` lists only the authenticated customer's orders. `GET /api/v1/merchant/orders`
and `GET /api/v1/merchant/orders/{id}` provide explicit tenant-level merchant visibility without
exposing JWT subjects, profiles or provider references. Both paths support `/{id}/history`.
Collections accept `limit` (1–100, default 20), opaque `cursor`, exact `status`, inclusive
`createdFrom` and exclusive `createdBefore`. Ordering is descending creation time, then UUID;
newer inserts cannot shift continuation pages. Keep filters unchanged while paging. Status
filters are live views; there is no multi-request snapshot. Unknown/repeated parameters,
including customer/tenant overrides, fail with coded 400 problems.

History records version, state, local transition time and a stable reason. State, history and
outbox commit atomically. Deferred reservation/payment resolution records both transitions.
History is bounded by the acyclic state machine and survives restart. Migration does not
invent old transitions: legacy orders start with a `LEGACY_SNAPSHOT` at migration observation
time. New orders have full histories.

`POST /api/v1/orders/{id}/cancel` accepts no body or query parameters. Only an owned CREATED
order whose checkout event has never begun dispatch and has no deferred payment evidence is
eligible. The publisher commits its dispatch fence before sending; an uncertain send therefore
permanently closes the cancellation window. Cancellation removes the unsent checkout event and
commits CANCELLED, history and `order.cancelled` in one transaction. Repeats return 200, including
across replicas/restart. Other outcomes return 409 `ORDER_NOT_CANCELLABLE`. No If-Match or extra
idempotency key is needed for this payload-free, lock-serialized command. No stock release or
payment reversal occurs. Merchant cancellation is not provided.

A worker expires only never-dispatched stale CREATED orders, using the same fence and database
row claims. `order.lifecycle.stale-after` defaults to `30m` (allowed 1 minute–7 days),
`order.lifecycle.poll-delay-ms` to 30000, and `order.lifecycle.worker-enabled` to true. Each batch
is bounded to 25; multiple instances skip locked orders. Time comes from an injectable Clock.
This threshold makes work eligible for expiry, not a guaranteed inventory-decision deadline.
If the publisher wins, the order continues normally. Dispatched CREATED orders, reserved orders,
and REVIEW_REQUIRED payments are never expired. Recovery of missing decisions remains operational
replay/reconciliation work. `runtime.outbox.enabled=false` can suspend publication for maintenance.

See the [full transition table](../../docs/adr/003-order-lifecycle-and-queries.md) and
[migration/rollout procedure](../../docs/database-migrations.md). New facts are `order.cancelled`,
`order.expired` and `order.rejected`, all v1. Rejection reasons distinguish insufficient stock
from authoritative payment decline. Existing checkout/reservation/payment payloads are unchanged.
