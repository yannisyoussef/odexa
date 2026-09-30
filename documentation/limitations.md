---
title: Known limitations
description: Keep the current Odexa product boundary explicit.
---

Odexa is not production-ready commerce. Current constraints include:

- USD only;
- 1–20 distinct products and 1–100 units per line;
- whole-basket reservation with no partial acceptance;
- full financial refunds only, with no partial or item refunds;
- no shipping, tax, discounts, returns, or fulfilment workflow;
- cancellation only before checkout dispatch;
- no automatic release for uncertain payment outcomes;
- no support UI or operator ledger for `REVIEW_REQUIRED` work;
- Stripe Test Mode and the deterministic simulator, not live-money
  certification;
- local Keycloak fixtures, not production identity onboarding;
- no frontend or cloud deployment supplied by this repository.

Refunds do not restock inventory. Never present a financial reversal as a
physical return or a cancelled checkout.
