# Payment providers and financial refunds

Status: Accepted

## Decisions

The payment service owns provider selection, immutable provider identity per payment,
refunds, and provider evidence. Existing rows remain simulator payments when the default
provider changes. Stripe is Test Mode only, behind the same business port as the independent
HTTP simulator. Odexa owns checkout state, so the Stripe adapter uses PaymentIntents;
Checkout Sessions would duplicate that orchestration. Only opaque payment method references
enter Odexa. SDK objects, secrets and provider payloads never enter public resources/events.

| Resource/current state | Evidence | Next state | Business effect |
| --- | --- | --- | --- |
| Payment PENDING | authoritative success | AUTHORIZED | payment.authorized |
| Payment PENDING | authoritative terminal failure | DECLINED | payment.declined |
| Payment PENDING | exhausted or asynchronous/unknown | REVIEW_REQUIRED | retain stock |
| Payment REVIEW_REQUIRED | authoritative success/failure | AUTHORIZED/DECLINED | matching payment fact |
| Payment REVIEW_REQUIRED | unavailable/unknown | REVIEW_REQUIRED | bounded delayed lookup |
| Payment AUTHORIZED/DECLINED | duplicate/late evidence | unchanged | none |
| Refund PENDING | authoritative success/failure | SUCCEEDED/FAILED | payment.refunded/refund.failed |
| Refund PENDING | exhausted or asynchronous/unknown | REVIEW_REQUIRED | no success/failure fact |
| Refund REVIEW_REQUIRED | authoritative success/failure | SUCCEEDED/FAILED | matching refund fact |
| Refund REVIEW_REQUIRED | unavailable/unknown | REVIEW_REQUIRED | bounded delayed lookup |
| Refund SUCCEEDED/FAILED | duplicate/late evidence | unchanged | none |

Provider work runs outside database transactions. Claim transactions use SKIP LOCKED,
leases and fencing tokens; state and outbox changes commit together. Automatic lookup
continues at capped backoff without repeating money-moving commands after the initial
bounded retry phase. Initial commands have a 23-hour safety deadline, below Stripe's
24-hour minimum idempotency retention. Missing references after that deadline stay uncertain;
absence from a search is never a decline or permission to create another charge.

Signed webhooks are durable **invalidation hints**: they accelerate authoritative provider
lookup for matching stored provider references. They do not apply snapshot statuses directly.
This avoids regressions from stale/out-of-order events and validates the current amount,
currency and resource linkage through the authenticated adapter. Metadata never authorizes
an order or creates a payment. Unknown or not-yet-linked events are durably acknowledged;
automatic reconciliation remains the recovery path for early delivery or lost responses.
Only event ID, provider, event type and object reference are retained, never raw bodies.
The inbox and lookup scheduling commit before acknowledgement. Exact raw bytes are signature
checked with a five-minute tolerance, including future timestamp rejection, and bounded at
64 KiB. Separate payment-service security allows only fixed webhook POST paths without JWT.
Signing secret rotation accepts multiple configured active secrets. Stripe events must be
Test Mode and from the configured account context (direct account only, no Connect).

Refunds are full-amount financial reversals. Same-tenant MERCHANT_ADMIN/MERCHANT_USER may
create them with a semantic idempotency key and requested amount/currency. Customers can
read only their own refunds; explicit merchant routes permit tenant-scoped reads. A locked
payment row and a partial unique index prevent multiple active/successful full refunds.
A definitively failed refund permits a new command/key; uncertainty never does.

Orders retain their checkout outcome CONFIRMED. Refund state is a separate financial
resource; payment.authorized remains a historical true fact. This deliberately avoids
conflating checkout, fulfilment, returns and financial status in one order enum. Clients
read refunds alongside orders. Successful refunds never restore inventory: there is no
physical return evidence. Cancellation remains restricted to safe pre-dispatch withdrawal.
A refund is not cancellation, and an uncertain payment cannot be refunded or released.

## Failure handling and limits

Durable reconciliation is at-least-once and coordinated across instances, not a ledger or
an operational support UI. Outages can hold stock indefinitely. Automatic command retries
are bounded; lookup retries continue at capped intervals because elapsed time does not
prove failure. Stripe payment methods needing customer action remain REVIEW_REQUIRED;
there is no browser authentication flow in this backend milestone. No live-money support,
partial refunds, fulfilment, returns, or automatic stock replenishment is claimed.

Deterministic HTTP fixtures and signed webhook tests require no external credentials.
Real Stripe Test Mode verification is separately opt-in and must fail when unconfigured.
