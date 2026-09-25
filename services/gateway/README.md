# Gateway

The edge forwards only the explicit public routes in `Routes`. Catalog's authenticated
`POST /api/internal/v1/products/batch` is private and deliberately absent. Existing checkout routes
accept either the legacy single-product request or bounded `items` input. OpenAPI reuses the
order contract for request, detail and collection representations.

The normal32768-byte request limit is unchanged. A compact maximum20-line basket with100units
per line and128-character payment reference is under2000bytes; whitespace still counts toward
the edge limit. Tests cover a maximum basket and exactly32768/32769bytes. Webhooks retain their
separate65536-byte signed-body limit. JWT/correlation forwarding, fixed upstreams, bounded
responses, no redirects and existing rate limits are unchanged.
