---
title: Local browser playground
description: Exercise the local gateway directly from the browser with explicit safety limits.
---

The Try it view is approved only for `http://localhost:8080`. It sends requests
from this browser directly to the gateway; the documentation server does not
proxy or store credentials.

Start the complete local stack first and configure its CORS policy to allow the
portal origin. Enter a short-lived local bearer token in memory. The browser
cannot set every header or bypass CORS, and a CORS error does not prove the API
did not process a mutation.

Catalog, inventory, checkout, cancellation, and refund operations change local
data. Use fixture tenants only, preserve required idempotency and precondition
headers, and inspect the operation description before sending.
