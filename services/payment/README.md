# Payment service

Provider-neutral payment service, default port **8084**. It exposes owned payment/refund queries, explicit merchant full-refund commands, and fixed signed provider webhook POSTs. Payment creation remains internal after inventory reservation. See the [HTTP contract](../../contracts/openapi/payment.json) for the complete surface.

## Durability and uncertainty

- `inventory.reserved` validates its snapshot, inserts the runtime inbox entry and creates a `payment` row in **one JDBC transaction**. The payment row itself is the durable job. `(tenant_id, order_id)` is unique; a conflicting payment snapshot fails and rolls back its inbox entry.
- Independent workers claim one due row with PostgreSQL `FOR UPDATE SKIP LOCKED`, increment the attempt count, and assign a random fencing token plus lease. Database time controls leases/backoff. No JVM lock coordinates work.
- The provider-neutral `PaymentProvider` port receives only domain records. Its simulator HTTP adapter calls the fixed configured provider endpoint, outside any transaction, with **the original order UUID on every attempt** as `Idempotency-Key`. Redirects are disabled; connect timeout is 2 seconds and request timeout is 5 seconds.
- Completion conditionally updates the leased row and appends `payment.authorized` or `payment.declined` to the runtime outbox in the same transaction. Stale workers cannot overwrite a reclaimed lease. A crash after provider success is safe to replay using provider idempotency.
- Only an explicit authoritative `DECLINED` response emits a decline. Timeouts, transport errors, unexpected status codes and malformed provider responses remain uncertain. Backoff defaults to 2/4/8/16 seconds; attempts default to 5, lease to 30 seconds, backoff cap to 60 seconds. Exhaustion, including a process dying on its final attempt, moves the row to `REVIEW_REQUIRED` once its lease expires. **No stock-release event is emitted.** Review is revisited by read-only provider reconciliation with capped backoff. No admin bypass endpoint is exposed.
- Runtime owns Kafka retries/DLT and at-least-once outbox publication. Malformed/unsupported events fail for DLT handling; known unrelated types are ignored.

## Configuration

Required environment: `DB_PASSWORD`, `PROVIDER_API_KEY` (same secret as simulator; never log it). `DB_URL` defaults to `jdbc:postgresql://localhost:5432/payment`, `DB_USER` to `payment`; `SIMULATOR_URL` defaults to `http://localhost:8085`. Runtime also consumes `KAFKA_BOOTSTRAP_SERVERS`, `OIDC_ISSUER`, `OIDC_JWKS`; `SERVER_PORT` overrides the port. Service-specific defaults are imported *after* runtime defaults so generic runtime defaults cannot replace the service database/port. Flyway applies this service's versioned migrations at startup; see the [migration procedure](../../docs/database-migrations.md).

Worker controls: `PAYMENT_WORKER_ENABLED`, `PAYMENT_POLL_MS`, `PAYMENT_MAX_ATTEMPTS` (1–20), `PAYMENT_LEASE_SECONDS` (10–600), `PAYMENT_BACKOFF_SECONDS` (positive), `PAYMENT_MAX_BACKOFF_SECONDS` (base–3600). Production transport requires trusted routing/TLS outside this local slice.

**Deferred:** live-money processing, frontend tokenization/authentication, partial refunds, and production onboarding. Stripe Test Mode, signed callbacks, reconciliation and full refunds are supported below.

## Validation

From the repository root with Java 25, run `./gradlew :services:payment:test :services:payment:integrationTest :services:payment:bootJar`. Unit tests cover worker decisions, stable idempotency headers, timeout handling, malformed events, tenant/owner delegation and retry limits. Integration-tagged tests cover PostgreSQL concurrency, inbox/result/outbox rollback, lease recovery/fencing, bounded uncertainty, actual Kafka consumption/DLT and runtime-protected HTTP lookups. Testcontainers uses PostgreSQL 17.6 and Kafka 4.1.0; tests skip without Docker, so CI must require Docker rather than treat a skipped run as validation. Execution results must be reported separately; source presence alone is not proof of passing tests.

## Providers, webhooks and refunds

See [ADR 004](../../docs/adr/004-payment-providers-and-financial-refunds.md) for transition tables.
`PAYMENT_PROVIDER=simulator` is the default. The provider is persisted per payment; existing
rows remain simulator-backed. Enable Stripe explicitly with `STRIPE_ENABLED=true` and
`PAYMENT_PROVIDER=stripe`, supply `STRIPE_API_KEY` (prefer a restricted `rk_test_` key), and
set comma-separated `STRIPE_WEBHOOK_SECRETS`. Never commit these values. Test Mode only;
live keys fail startup. Keep simulator credentials available for historical simulator rows.
A restricted Stripe key needs PaymentIntent read/write and Refund read/write permissions.
The pinned official Java SDK is 33.4.2, using API version `2026-08-26.dahlia`. Each HTTP exchange has
a five-second total deadline and 16 KiB response bound; SDK retries are disabled. Application
command retries use stable order/refund UUID keys. The initial phase stops before 23 hours;
reconciliation only reads. Provider unavailability, missing search results and action-required
payments remain indeterminate. Stripe card failures are terminal only after intent cancellation.

Configure Stripe Test Mode snapshot events at `POST /api/v1/webhooks/stripe` with the SDK's
API version. Relevant events include `payment_intent.succeeded`, `payment_intent.canceled`,
`payment_intent.payment_failed`, `refund.created`, `refund.updated` and `refund.failed`.
The endpoint has no bearer-token requirement; it verifies the exact raw body with Stripe's
SDK before durable acceptance. Direct-account Test Mode only, no Connect/event contexts.
A five-minute past/future timestamp bound and durable provider/event ID deduplication prevent
replays. Rotate signing secrets by temporarily configuring old,new together, then remove old.
Never enable request/header/body debug logging. Provider payloads are not retained.

Webhooks are invalidation hints, not commands or trusted state snapshots. The database stores
only safe event ID/type/object reference and schedules authenticated lookup for matching known
references. Unknown types and early/unlinked events receive 204 after durable acceptance;
periodic reconciliation recovers missing references through provider lookups. Returned amount,
currency, internal resource linkage and Test Mode are validated before applying evidence.
No matches or multiple matches remain uncertain. Missing Stripe refund references are searched
only within the known PaymentIntent and first ten refunds; larger ambiguous histories remain
REVIEW_REQUIRED for future operational handling. Provider/API metadata must not be edited.

`POST /api/v1/merchant/payments/{orderId}/refunds` requires same-tenant MERCHANT_ADMIN or
MERCHANT_USER, `Idempotency-Key` (1–128 alphanumeric, underscore or hyphen), and JSON
`{"amountMinor":2500,"currency":"USD"}` matching the entire payment. 201 records a durable
request; 200 replays the same semantic command; 409 rejects conflicting or unsafe requests.
One active or successful full refund is allowed. A definitively failed refund allows a new key.
Customers read only their own `/api/v1/payments/{orderId}/refunds` and `/{refundId}`;
merchant reads use the explicit merchant prefix. Lists are bounded to 50, ordered by UUID,
with `nextCursor`; restart a listing to see concurrent insertions preceding that cursor.

Refunds expose PENDING, SUCCEEDED, FAILED and REVIEW_REQUIRED. Success emits
`payment.refunded`; definite failure emits `refund.failed`. Neither changes the checkout
outcome or restores stock. No provider IDs, keys, failure payloads, or worker leases are exposed.
Uncertain payment outcomes retain stock; uncertain refunds forbid another refund command.
The worker automatically revisits both with bounded backoff and database leases/fencing.
No admin bypass/reconcile endpoints are provided. A support UI/ledger is future work.

Explicit external check (not part of ordinary tests): with `STRIPE_API_KEY` supplied securely
in the environment, run `./gradlew :services:payment:stripeTestMode`. It creates a USD 1.00
Test Mode payment, retries the same command, then fully refunds it. Missing credentials fail
clearly; they never produce a skipped success. It does not certify real webhook delivery.
For that, configure the endpoint above, use an isolated Test Mode account, and inspect
Odexa's payment/refund resources for convergence. No live-money verification is supported.
