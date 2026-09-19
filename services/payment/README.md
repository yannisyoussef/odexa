# Payment service

Internal payment orchestration for Odexa. Default HTTP port **8084**; only `GET /api/v1/payments/{orderId}` is public through the gateway. Runtime verifies bearer issuer/audience; this service queries by both JWT tenant and subject. There is no tenant-header override, role-based ownership bypass, or public creation endpoint.

## Durability and uncertainty

- `inventory.reserved` validates its snapshot, inserts the runtime inbox entry and creates a `payment` row in **one JDBC transaction**. The payment row itself is the durable job. `(tenant_id, order_id)` is unique; a conflicting payment snapshot fails and rolls back its inbox entry.
- Independent workers claim one due row with PostgreSQL `FOR UPDATE SKIP LOCKED`, increment the attempt count, and assign a random fencing token plus lease. Database time controls leases/backoff. No JVM lock coordinates work.
- The provider-neutral `PaymentProvider` port receives only domain records. Its simulator HTTP adapter calls the fixed configured provider endpoint, outside any transaction, with **the original order UUID on every attempt** as `Idempotency-Key`. Redirects are disabled; connect timeout is 2 seconds and request timeout is 5 seconds.
- Completion conditionally updates the leased row and appends `payment.authorized` or `payment.declined` to the runtime outbox in the same transaction. Stale workers cannot overwrite a reclaimed lease. A crash after provider success is safe to replay using provider idempotency.
- Only an explicit authoritative `DECLINED` response emits a decline. Timeouts, transport errors, unexpected status codes and malformed provider responses remain uncertain. Backoff defaults to 2/4/8/16 seconds; attempts default to 5, lease to 30 seconds, backoff cap to 60 seconds. Exhaustion, including a process dying on its final attempt, moves the row to `REVIEW_REQUIRED` once its lease expires. **No stock-release event is emitted.** Review is deliberately terminal for automatic processing; no reconciliation/admin endpoint is implemented in this slice.
- Runtime owns Kafka retries/DLT and at-least-once outbox publication. Malformed/unsupported events fail for DLT handling; known unrelated types are ignored.

## Configuration

Required environment: `DB_PASSWORD`, `PROVIDER_API_KEY` (same secret as simulator; never log it). `DB_URL` defaults to `jdbc:postgresql://localhost:5432/payment`, `DB_USER` to `payment`; `SIMULATOR_URL` defaults to `http://localhost:8085`. Runtime also consumes `KAFKA_BOOTSTRAP_SERVERS`, `OIDC_ISSUER`, `OIDC_JWKS`; `SERVER_PORT` overrides the port. Service-specific defaults are imported *after* runtime defaults so generic runtime defaults cannot replace the service database/port. Flyway applies this service's versioned migrations at startup; see the [migration procedure](../../docs/database-migrations.md).

Worker controls: `PAYMENT_WORKER_ENABLED`, `PAYMENT_POLL_MS`, `PAYMENT_MAX_ATTEMPTS` (1–20), `PAYMENT_LEASE_SECONDS` (10–600), `PAYMENT_BACKOFF_SECONDS` (positive), `PAYMENT_MAX_BACKOFF_SECONDS` (base–3600). Production transport requires trusted routing/TLS outside this local slice.

**Deferred:** Stripe integration, real payment credentials/tokenization, asynchronous webhooks, reconciliation tooling, refunds and production provider onboarding. The synchronous simulator is authoritative; no callback URL or testing endpoint is added.

## Validation

From the repository root with Java 25, run `./gradlew :services:payment:test :services:payment:integrationTest :services:payment:bootJar`. Unit tests cover worker decisions, stable idempotency headers, timeout handling, malformed events, tenant/owner delegation and retry limits. Integration-tagged tests cover PostgreSQL concurrency, inbox/result/outbox rollback, lease recovery/fencing, bounded uncertainty, actual Kafka consumption/DLT and runtime-protected HTTP lookups. Testcontainers uses PostgreSQL 17.6 and Kafka 4.1.0; tests skip without Docker, so CI must require Docker rather than treat a skipped run as validation. Execution results must be reported separately; source presence alone is not proof of passing tests.
