# URL Shortener

A URL shortening service with click analytics and the reliability and abuse controls a
public redirector actually needs. Java 21 / Spring Boot 3.2, Postgres for the system of
record, Redis for cache and rate limiting.

Start with the [engineering summary](docs/engineering-summary.md) for the reasoning behind
the design; this file covers how to run it.

---

## Quick start

Requires Java 21, Maven 3.9+, and Docker.

```bash
docker compose up -d --wait     # Postgres + Redis
mvn spring-boot:run             # Flyway migrates on startup
```

The service listens on `http://localhost:8080`. Interactive API docs: `http://localhost:8080/docs`.

```bash
mvn test      # unit tests, no Docker needed
mvn verify    # unit + integration tests against real Postgres and Redis
```

### Local development against localhost targets

By default the service refuses to shorten URLs that resolve to private or loopback
addresses. To point links at something on your own machine:

```bash
BLOCK_PRIVATE_NETWORKS=false mvn spring-boot:run
```

Never set this in a deployed environment — see
[ADR-0004](docs/adr/0004-ssrf-and-abuse-controls.md).

---

## API

### Create a link

```bash
curl -sX POST localhost:8080/links \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-4417' \
  -d '{"url": "https://example.com/a/very/long/path", "alias": "q3-report", "expiresAt": "2026-12-31T23:59:59Z"}'
```

`alias`, `expiresAt` and the `Idempotency-Key` header are all optional.

```json
{
  "code": "q3-report",
  "shortUrl": "http://localhost:8080/q3-report",
  "longUrl": "https://example.com/a/very/long/path",
  "createdAt": "2026-08-25T10:00:00Z",
  "expiresAt": "2026-12-31T23:59:59Z",
  "active": true
}
```

`201` on creation. `200` when an `Idempotency-Key` replay returns the link that already
exists — retrying a create never mints a second code.

### Follow a link

```bash
curl -si localhost:8080/q3-report
```

`302` with the target in `Location`, `404` for an unknown code, `410` for one that has
been retired or has expired. The response carries `Cache-Control: no-store` so the hop is
never cached — without that, clicks become invisible and revocation stops working.

### Statistics

```bash
curl -s 'localhost:8080/links/q3-report/stats?days=7&referrers=5'
```

```json
{
  "code": "q3-report",
  "totalClicks": 1284,
  "daily": [{"day": "2026-08-24", "clicks": 402}],
  "topReferrers": [{"host": "news.example.com", "clicks": 890}, {"host": "(direct)", "clicks": 394}],
  "freshnessSeconds": 2
}
```

Counts are eventually consistent. `freshnessSeconds` states the lag in the payload rather
than leaving callers to discover it — see [ADR-0003](docs/adr/0003-analytics-pipeline.md).

### Retire a link

```bash
curl -sX DELETE localhost:8080/links/q3-report   # 204
```

Soft delete. The code is never reissued, so an old link fails closed rather than landing
somewhere unexpected.

### Ownership and API keys

Links can be owned by an API key. Ownership governs **administration** of a link
(statistics, deletion) — never redirection, which is always public.

Authentication rolls out in three states via `AUTH_MODE` (`DISABLED` → `OPTIONAL` →
`REQUIRED`); the service defaults to `OPTIONAL`. See
[ADR-0005](docs/adr/0005-link-ownership-and-authentication.md).

```bash
# Issue a key (requires the out-of-band admin token; unset means the endpoint refuses all)
ADMIN_TOKEN=secret mvn spring-boot:run
curl -sX POST localhost:8080/api-keys \
  -H 'Content-Type: application/json' -H 'X-Admin-Token: secret' \
  -d '{"name": "reporting-service"}'
```

The key is returned **once**; only its SHA-256 is stored. Pass it as `X-API-Key`:

```bash
curl -sX POST localhost:8080/links -H 'X-API-Key: sk_...' \
  -H 'Content-Type: application/json' -d '{"url": "https://example.com/x"}'
```

| Mode | Unowned (legacy) links | Owned links |
|---|---|---|
| `DISABLED` | Anyone | Anyone |
| `OPTIONAL` | Anyone (grandfathered) | Owner only |
| `REQUIRED` | Refused | Owner only |

Backfill unowned links **before** setting `REQUIRED`.

### Errors

Every failure returns the same envelope, so callers branch on `error`, not on prose:

```json
{"error": "alias_taken", "message": "The alias 'q3-report' is already in use", "details": [], "timestamp": "..."}
```

| Code | Status | Meaning |
|---|---|---|
| `invalid_url` | 400 | Not http(s), malformed, self-referential, or not publicly routable |
| `invalid_alias` | 400 | Wrong shape, or a reserved word |
| `invalid_expiry` | 400 | `expiresAt` is not in the future |
| `validation_failed` | 400 | Request body or parameters rejected |
| `alias_taken` | 409 | Custom alias already in use |
| `link_not_found` | 404 | No such code |
| `link_expired` | 410 | Code existed; retired or past expiry |
| `rate_limited` | 429 | Per-IP budget exhausted |
| `unauthenticated` | 401 | Missing or invalid `X-API-Key` |
| `forbidden` | 403 | Valid key, but it does not own that link |
| `idempotency_key_conflict` | 409 | Key already used by another owner |
| `code_generation_failed` | 503 | Could not allocate a unique code |

---

## Operations

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Liveness and readiness (includes Postgres and Redis) |
| `/actuator/prometheus` | Metrics |
| `/docs` | OpenAPI / Swagger UI |

Metrics worth alerting on:

- `shortener_clicks_dropped_total` — the analytics buffer is saturated; clicks are being lost.
- `shortener_clicks_flush_failures_total` — rollup writes are failing.
- `shortener_cache_errors_total` — Redis is degraded; the service is serving from Postgres alone.
- `shortener_ratelimit_errors_total` — the limiter is failing open.

Configuration is entirely in `application.yml` under `app.*`, overridable by environment
variable (`BASE_URL`, `DB_URL`, `REDIS_HOST`, `BLOCK_PRIVATE_NETWORKS`, …).

---

## Documentation

- [Final engineering summary](docs/engineering-summary.md) — start here
- [Architecture overview](docs/architecture.md) — components, control flow, data model
- [AI execution log](docs/ai-execution-log.md) — method, traceability, quality gates
- [Testing approach, limitations and trade-offs](docs/testing-and-tradeoffs.md)
- Scenarios:
  - [Greenfield](docs/scenarios/01-greenfield.md) — building the core service
  - [Brownfield](docs/scenarios/02-brownfield.md) — adding ownership to a live service
  - [Ambiguous](docs/scenarios/03-ambiguous.md) — normalising an underspecified requirement
- Decision records:
  - [ADR-0001 — Short code generation](docs/adr/0001-short-code-generation.md)
  - [ADR-0002 — Redirect semantics and caching](docs/adr/0002-redirect-semantics-and-caching.md)
  - [ADR-0003 — Analytics pipeline](docs/adr/0003-analytics-pipeline.md)
  - [ADR-0004 — SSRF and abuse controls](docs/adr/0004-ssrf-and-abuse-controls.md)
  - [ADR-0005 — Link ownership and authentication](docs/adr/0005-link-ownership-and-authentication.md)

---

## Build status — read this

This repository was authored in an environment without access to Maven Central, so
`mvn verify` has **not** been executed against it. Do not treat it as green until you have
run it yourself.

What *was* verified: the algorithmic cores — code generation, the SSRF address rules,
referrer reduction, cache TTL clamping, click aggregation, the authorisation truth table,
API-key hashing and client-IP extraction — were extracted verbatim into a standalone Java
program and executed against the same cases the JUnit suite asserts. 45,104 assertions
pass. That exercise found one real defect: a comment
in `RedirectController` claimed the route pattern prevented `/links` from being captured by
the redirect mapping, which is false — single-segment paths do match, and it is the
reserved-word list that makes this safe. The comment was corrected.

What that harness cannot tell you: whether Spring wiring is correct, whether the Flyway
schema matches the JPA mappings, or whether the `ON CONFLICT` upsert is valid SQL. Those
are exactly what `LinkApiIT` exists to prove, and it needs a real Maven build and a Docker
daemon to run.
