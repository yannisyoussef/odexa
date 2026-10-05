---
title: Authentication and tenants
description: Use verified Keycloak JWTs without supplying tenant authority in request data.
---

Resource services validate the JWT signature, issuer, expiry, and `odexa-api`
audience. The administrator-assigned UUID `tenant_id` claim is the only tenant
authority. Customer ownership also comes from `sub`; write roles come from
`realm_access.roles`.

Customer operations require `CUSTOMER`. Merchant operations require
`MERCHANT_ADMIN` or `MERCHANT_USER`. Support and platform roles are vocabulary,
not cross-tenant bypasses. Unknown, other-tenant, and other-owner resources use
the same not-found response where disclosure would leak existence.

## Try it safely

The portal stores a bearer token in page memory only and sends it directly from
the browser to the selected local gateway. Obtain a short-lived local fixture
token through the repository smoke tooling. Never paste a production token,
provider key, password, or cookie into the portal.
