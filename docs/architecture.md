# Architecture

## The shape of the problem

A URL shortener looks trivial and is not, because two of its operations have opposite
characteristics:

| | Create | Redirect |
|---|---|---|
| Volume | Low | Very high |
| Latency tolerance | Hundreds of ms | Tens of ms |
| Consistency need | Strong (a code must be unique) | Read-your-writes, eventually |
| Failure cost | Retry | A broken link, possibly printed on something |

Almost every decision in this service follows from protecting the redirect path and
accepting cost on the create path.

## Components

```
                    ┌──────────────────────────────────────────┐
   POST /links ────▶│ LinkController                           │
                    │   → UrlValidator      (SSRF, scheme)     │
                    │   → ShortCodeGenerator(CSPRNG base62)    │
                    │   → LinkWriter        (tx per attempt)   │──▶ Postgres
                    └──────────────────────────────────────────┘
                                                                    │
                    ┌──────────────────────────────────────────┐    │
   GET /{code} ────▶│ RateLimitFilter ──▶ RedirectController   │    │
                    │   → LinkService.resolve                  │    │
                    │       → LinkCache  ─────────────────────────▶ Redis
                    │       → LinkWriter (on miss) ───────────────▶ Postgres
                    │   → ClickEventSink  (fire and forget)    │
                    └──────────────────────────────────────────┘
                                     │
                          bounded in-memory queue
                                     │  every 2s, batched
                                     ▼
                          ClickRollupWriter ──▶ Postgres (link_click_daily)
```

Package layout mirrors this: `web` (HTTP contract), `service` (application logic),
`domain` + `repo` (persistence), `analytics` (click pipeline), `security` (rate limiting),
`error` (fault-to-HTTP mapping), `config` (typed properties).

## Control flow: the redirect

1. `RateLimitFilter` increments a per-IP fixed-window counter in Redis. Fails open.
2. `LinkCache.lookup` — a hit returns either the target URL or a positively cached
   *absence*. Negative caching exists so that a flood of requests for nonexistent codes
   cannot become a flood of Postgres queries.
3. On a miss, Postgres is queried. If the row is absent the miss is cached; if it is
   present but inactive or expired the result is `410` and is deliberately **not** cached
   as missing, because that would downgrade the answer to `404`.
4. The cache entry TTL is clamped to the link's own expiry, so an expired link cannot
   keep redirecting until the cache lapses.
5. The click is handed to a bounded queue and the `302` is returned. Nothing about
   analytics is on the response path.

## Control flow: creation

1. Validate the URL (scheme, userinfo, length, self-reference, resolved address).
2. If an `Idempotency-Key` was supplied, return the existing link if one exists.
3. Allocate a code — the caller's alias, or a CSPRNG draw.
4. Insert. **The database is the arbiter of uniqueness.** A check-then-insert would still
   race; the unique index does not.
5. On a collision, retry with a fresh code (bounded). A custom alias is never retried —
   a different code is not what the caller asked for, so that is a `409`.

Step 5 is the reason `LinkWriter` is a separate bean. A constraint violation marks the
surrounding transaction rollback-only, so a retry loop inside a transaction cannot
recover. Each attempt needs `REQUIRES_NEW`, which means it must cross a proxy boundary.

## Data model

```sql
link(id, code UNIQUE, long_url, created_at, expires_at, active,
     idempotency_key, owner_key_id -> api_key.id)
api_key(id, key_hash UNIQUE, name, created_at, active)
link_click_daily(code, click_day, referrer_host, clicks)   -- PK on all three
```

`owner_key_id` is nullable: NULL means the link predates ownership. Those rows are
grandfathered while `AUTH_MODE=OPTIONAL` and must be backfilled before `REQUIRED`.

Two properties are load-bearing:

- **Soft delete.** `active = false` rather than `DELETE`, so a code is never reissued and
  an old printed link fails closed instead of resolving somewhere new.
- **Pre-aggregated clicks.** No raw event table. The rollup is written with
  `ON CONFLICT ... DO UPDATE SET clicks = clicks + EXCLUDED.clicks`, so concurrent flushes
  from multiple instances converge without locking — the database performs the addition,
  not the application.

## Failure behaviour

| Failure | Behaviour |
|---|---|
| Redis down | Redirects and creates continue against Postgres. Rate limiting is disabled (fails open). Latency rises; correctness does not change. |
| Postgres down | Cached redirects continue to work. Creates fail with `503`. |
| Analytics queue saturated | Clicks are dropped and counted (`shortener_clicks_dropped_total`). Redirects are unaffected. |
| Rollup flush fails | The batch is dropped, counted and logged. It is not requeued: a poison batch would loop forever and starve fresh events. |
| Code space pressure | After N collisions, `503` — an operational signal, not a `500`. |
| `api_key` table unavailable | Redirects are unaffected — they are never authenticated. Administration fails. |

The consistent rule: **the redirect is the product.** Analytics, rate limiting and caching
are all allowed to degrade to protect it. The one thing that never fails open is URL
validation, because there the failure mode is not a slow response but an attacker using
the organisation's domain as cover.

## What is deliberately absent

- **User accounts and key scopes.** Links are owned by API keys (see
  [ADR-0005](adr/0005-link-ownership-and-authentication.md)), but there are no user
  accounts, no key expiry and no read-only scopes.
- **A durable event log.** Kafka behind the `ClickEventSink` interface is a one-class
  change. It is not justified while counts are informational.
- **Multi-region.** Single-region, single-writer. Geo-distributed reads would need a
  replicated cache and a decision about code allocation across regions.
