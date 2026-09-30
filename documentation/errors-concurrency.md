---
title: Errors, idempotency, and concurrency
description: Handle RFC 9457 problems, correlation IDs, retries, and optimistic conflicts.
---

Error bodies use `application/problem+json` with RFC 9457 fields plus stable
domain codes where applicable. Branch on HTTP status and documented code, not
human detail text. Carry `X-Correlation-ID` as a canonical UUID when tracing a
request; do not place customer or credential data in it.

Retry only when semantics allow it:

- reuse the same idempotency key and identical request after an uncertain
  order or refund response;
- re-read after 412 and make a new business decision with the new ETag;
- correct a missing precondition after 428;
- back off on transient availability failures;
- do not reinterpret a timeout as an authoritative payment decline.

Database uniqueness, row locks, transactional inboxes, and fenced leases make
concurrent retries converge. They do not turn a changed request into a retry.
