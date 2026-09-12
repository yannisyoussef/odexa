# 002 — Tenant identity and local trust boundaries

Status: accepted

## Decision

Keycloak owns credentials and signed OIDC tokens. Services validate signature, issuer, expiry and `odexa-api` audience. The configured JWK backchannel may differ from the external issuer URL but never changes issuer validation.

Tenant identity is the administrator-assigned UUID `tenant_id` claim, not a header or body property. Resource queries include tenant predicates, and customer order/payment reads additionally include the token subject. Roles grant operations, not implicit cross-tenant access. Support/platform roles are vocabulary for future policies, not bypasses. Missing/invalid tenant claims fail closed. Keycloak tenant attributes must remain administrator-controlled when onboarding is introduced.

Return `404` for absent or inaccessible tenant/customer resources so guessed UUIDs do not disclose existence. Return `403` for authenticated callers without the operation's role. Money and order status are server-owned; clients cannot mass-assign them.

The gateway accepts only fixed route/method pairs and configured upstreams. It does not forward cookies, host/forwarding headers or arbitrary URLs. Requests, responses and upstream waits are bounded; a process-wide rate bucket is a local abuse baseline, not a distributed production rate limiter. Internal services revalidate end-user bearer tokens.

The local provider is off the gateway, uses a generated secret and constant-time comparison, and offers no arbitrary webhook URL. Its idempotency records survive restarts. It is a deterministic sandbox provider, not a live payment integration.

## Local versus production

Compose publishes only loopback gateway/Keycloak ports. Services have separate PostgreSQL databases/users. Generated credentials are ignored, private files; no fixed password is stored in source. Kafka uses a private Compose network with trusted producers. That is an explicit local trust assumption, **not broker authentication**: production needs TLS, service identities, topic ACLs and restricted DLT access.

The development realm disables registration and enables password grants only for fixture automation. Production must disable that client, configure HTTPS and authorization code + PKCE, restrict administration, manage tenant membership, and define audited support access. No DNS, certificates or production hosting are configured by this repository.

Health responses contain no details. Other actuator routes are blocked at the edge and protected/disabled at service boundaries. Logs are structured; correlation is propagated through HTTP and events without logging credentials or provider payloads. A tracing exporter and secured operational metrics access remain deployment work.
