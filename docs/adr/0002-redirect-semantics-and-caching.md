# ADR-0002 — Redirect semantics and caching

**Status:** Accepted

## Context

The redirect is the product. It must be fast, and it must remain truthful after the link
changes.

## Decision 1: 302, not 301

A `301 Moved Permanently` is cached by browsers and intermediaries more or less forever.
That buys one saved round trip and costs three things:

- **Analytics become fiction.** Repeat visits never reach the service. The reported number
  is not clicks, it is first-clicks-per-browser-cache.
- **Expiry becomes unenforceable.** A link that has expired keeps working in every browser
  that saw it before.
- **Revocation becomes impossible.** A link published with the wrong destination cannot be
  corrected for the people who already followed it.

The service returns `302` with `Cache-Control: no-store, no-cache, must-revalidate`. The
extra round trip is the price of being able to count, expire and revoke.

`Referrer-Policy: no-referrer` is also set, so the short code is not leaked onward to the
destination site — which would otherwise let the destination enumerate the shortener's
namespace from its own logs.

## Decision 2: cache-aside in Redis, with negative caching

Reads go to Redis first, fall through to Postgres, and populate on the way back.

**Negative caching is included.** Without it, a flood of requests for codes that do not
exist — a scanner, a broken integration, a stale QR code — passes straight through the
cache to Postgres, which is cache penetration and a straightforward way to take the
database down. Misses are cached with a much shorter TTL (30s vs 10m) so that a code
created moments after someone 404'd on it goes live promptly. Creation also evicts the key
explicitly, so the window is usually zero.

**The TTL is clamped to the link's own expiry.** A link expiring in 30 seconds is cached
for 30 seconds, not 10 minutes. Without this, expiry is enforced by the database but
silently ignored by the cache, which is the kind of bug that is invisible until it matters.

**An expired link is never negatively cached.** It is a distinct `410` answer; caching it
as "missing" would downgrade the response to `404` and lose the distinction between a typo
and a retired link.

## Decision 3: the cache fails open

Every Redis interaction is wrapped. If Redis is unreachable the service serves from
Postgres alone — slower, still correct. The cache is an accelerator, never a dependency
for correctness.

The one asymmetry worth naming: a failed *eviction* on delete means a retired link can
keep resolving for up to one TTL. That is why the row is deactivated **before** the
eviction is attempted — the durable state is already correct, and the cache is at worst
briefly stale. It also means the cache must never be used for an authorisation decision.
