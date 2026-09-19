# Payment simulator

Independent internal HTTP provider, default port **8085**, with its **own database**. It does not import `libraries/runtime` or use end-user bearer authentication.

- `POST /provider/v1/payments`: required `X-Provider-Key`, `Idempotency-Key` equal to the body order UUID, and JSON `{orderId, amountMinor, currency, paymentMethod}`. Positive integer minor-unit amounts and USD only.
- `pm_approved` deterministically returns `AUTHORIZED`; `pm_declined` returns `DECLINED`. These are documented sandbox references, not live card data or artificial test-control endpoints.
- New results return 201 with Location; exact semantic retries return 200 with the same payment result with id/status/orderId/amountMinor/currency. Database uniqueness arbitrates concurrent requests. A changed valid payload for an existing order key returns 409, without overwriting the original result. The entire payload and outcome are stored atomically, so idempotency survives replicas/restarts.
- `GET /provider/v1/payments/{id}` also requires the provider key. Unknown IDs return 404.
- Missing, repeated or incorrect provider keys return 401. The required configured secret is hashed and compared using constant-time fixed-length digest comparison; no keys, payment request bodies, or underlying exceptions are logged. Health GETs are the only unauthenticated operational endpoint. Other non-provider paths are denied.
- Caller-supplied callback URLs, arbitrary outbound destinations, and testing/control endpoints are rejected. Signed callbacks use a fixed configured target. Unknown request properties are rejected. There is no live-money capability.

## Configuration and validation

Required environment: `DB_PASSWORD`, `PROVIDER_API_KEY` (same secret as payment). Optional: `DB_URL` (default `jdbc:postgresql://localhost:5432/simulator`), `DB_USER` (default `simulator`), `SERVER_PORT` (default 8085). Never reuse the payment service's database. Flyway applies this service's own versioned migrations; runtime inbox/outbox tables are not created. See [database upgrades](../../docs/database-migrations.md) for existing v0.1.0 data. Keep this provider off the public gateway and use trusted networking/TLS outside local development.

From the repository root with Java 25, run `./gradlew :services:payment-simulator:test :services:payment-simulator:integrationTest :services:payment-simulator:bootJar`. Unit tests cover deterministic decisions and secret validation. Integration-tagged Testcontainers tests use real HTTP plus PostgreSQL 17.6 for concurrent idempotency, collisions, auth, malformed input and absent callback/testing routes. They skip without Docker; CI must perform a Docker preflight. No claim of successful execution should be inferred until those commands are actually run.

## Reconciliation, refund scenarios and delivery

The original references remain compatible. Additional opaque references:

| Reference | Payment | Refund |
| --- | --- | --- |
| `pm_lost_response` | Commits AUTHORIZED, commands return 503; lookup recovers | SUCCEEDED |
| `pm_reconcile_declined` | Commits DECLINED, commands return 503; lookup recovers | Not allowed |
| `pm_unknown` | REVIEW_REQUIRED indefinitely | Not allowed |
| `pm_refund_declined` | AUTHORIZED | FAILED |
| `pm_refund_unknown` | AUTHORIZED | REVIEW_REQUIRED indefinitely |
| `pm_refund_lost` | AUTHORIZED | Commits SUCCEEDED, commands return 503; lookup recovers |

`GET /provider/v1/payments/by-order/{orderId}` recovers a lost creation response.
`POST /provider/v1/refunds` accepts refundId/paymentId/amountMinor/currency and requires the
refund UUID as Idempotency-Key. `GET /provider/v1/refunds/{refundId}` queries the command.
All routes require the provider API key. Full refunds are serialized per payment and survive
restarts. No callback URL, amount override, time-control endpoint, or raw card data is accepted.

Set `SIMULATOR_WEBHOOKS_ENABLED=true`, a fixed `SIMULATOR_WEBHOOK_URL` ending in
`/api/v1/webhooks/simulator`, and independent `SIMULATOR_WEBHOOK_SECRET`. Compose does this
using bootstrap-generated local credentials. Events are transactionally queued with provider
results, claimed with leases/fencing, HMAC-SHA256 signed as `t=epoch,v1=hex` over
`epoch.raw-body`, and retried with capped backoff until 2xx. Callback timestamps/signatures
are refreshed on retry, while event IDs stay fixed. Restarting either application preserves
outcomes and delivery work. Only the configured destination receives callbacks; redirects
are never followed. No external internet or Stripe credentials are needed.
