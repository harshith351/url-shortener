# Final engineering summary

## What was built

A URL shortener with create, redirect, expiry, idempotent creation, click analytics, link
ownership, and the reliability and abuse controls a public redirector needs. Java 21 /
Spring Boot 3.2, Postgres as system of record, Redis for cache and rate limiting.

Three scenarios were executed against it:

| Scenario | Work |
|---|---|
| [Greenfield](scenarios/01-greenfield.md) | The core service, from an empty directory |
| [Brownfield](scenarios/02-brownfield.md) | Adding ownership to a service that already had clients and unowned rows |
| [Ambiguous](scenarios/03-ambiguous.md) | "Users should be able to see how their links are performing" |

## Plan and rationale

One observation drove the whole design: **create and redirect have opposite
characteristics.** Create is low-volume, latency-tolerant and needs strong consistency;
redirect is high-volume, latency-critical and tolerates eventual consistency. Every
significant decision reduces to *protect the redirect path, accept cost on the create path*.

That single principle explains, without further justification:

- Analytics off the hot path, pre-aggregated ([ADR-0003](adr/0003-analytics-pipeline.md))
- Cache-aside with negative caching, failing open ([ADR-0002](adr/0002-redirect-semantics-and-caching.md))
- Authentication on administration only, never on redirects ([ADR-0005](adr/0005-link-ownership-and-authentication.md))
- CSPRNG codes despite the collision handling they require ([ADR-0001](adr/0001-short-code-generation.md))

The one place that principle is deliberately overridden is URL validation, which fails
**closed** — because there the failure mode is not a slow response but an attacker using
the organisation's domain as cover ([ADR-0004](adr/0004-ssrf-and-abuse-controls.md)).

## Artifacts

| Artifact | Location |
|---|---|
| Runnable service | `src/main/`, `docker-compose.yml` |
| Schema migrations | `src/main/resources/db/migration/` |
| API definition | OpenAPI at `/docs`; contract in `README.md` |
| Tests | `src/test/` — unit + Testcontainers integration |
| CI quality gates | `.github/workflows/ci.yml` |
| Architecture | `docs/architecture.md` |
| Decision records | `docs/adr/0001`–`0005` |
| Scenarios | `docs/scenarios/` |
| AI execution log | `docs/ai-execution-log.md` |
| Testing, limits, trade-offs | `docs/testing-and-tradeoffs.md` |
| Verification harness | `tools/verify/` |

## Risks and trade-offs

The full table is in [testing and trade-offs](testing-and-tradeoffs.md). The ones that
would matter first in production:

| Risk | Current state | Mitigation / trigger |
|---|---|---|
| **Build never executed** | `mvn verify` has not run | Run it before trusting anything here |
| No key rotation or scopes | Keys revocable, never expire | Needed before real multi-tenant use |
| DNS rebinding | Validated at creation only | Egress proxy, or re-validate at redirect |
| No malicious-URL screening | None | Safe Browsing or equivalent before public exposure |
| At-most-once click delivery | Bounded loss on crash | Durable log if counts become billing-grade |
| Unowned legacy links | Grandfathered in `OPTIONAL` | **Backfill before setting `REQUIRED`** |
| Global idempotency-key uniqueness | Cross-owner replay refused | Scope to `(owner, key)` after backfill |
| Fixed-window rate limiting, per-IP | Admits 2× at boundaries; trusts `X-Forwarded-For` | Only deploy behind a proxy that overwrites it |
| No load testing | Latency claims are reasoned, not measured | k6 against the redirect path, with and without Redis |

## Validation

- **Unit tests** on the components where a defect is expensive and invisible: SSRF rules,
  code generation, cache and expiry semantics, click aggregation, the authorisation truth
  table (all 13 combinations, enumerated).
- **Integration tests** against real Postgres and Redis, because substitutes cannot catch a
  migration/mapping mismatch or invalid `ON CONFLICT` SQL.
- **Standalone harness** — 45,104 assertions, 0 failures — covering the algorithmic cores,
  built because Maven Central was unreachable. It found one real defect: a comment claiming
  the route pattern protected `/links`, which is false.
- **CI gates**: tests, OWASP dependency scan at CVSS ≥ 7, SpotBugs/FindSecBugs, coverage.

## Assumptions

1. Click counts are informational — not billed, not SLA-reported.
2. Seconds-fresh analytics are acceptable; stated in every response as `freshnessSeconds`.
3. No per-visitor identity is needed. Chosen because it is the reversible direction:
   adding uniques later is additive; un-collecting identifiers is not.
4. The service runs behind a proxy that overwrites `X-Forwarded-For`.
5. Single region, single writer.
6. API keys are machine credentials, never user-chosen — the basis for using SHA-256
   rather than an adaptive KDF.

Each of these is falsifiable, and each has a documented consequence if wrong.

## Limitations

Beyond the risk table: no UI, no bulk creation, no key scopes, no retention policy for
analytics (currently indefinite, which is a decision nobody has actively made), no
multi-region story, and no backfill tooling — which the auth rollout depends on, making it
the top of the follow-up list rather than a nice-to-have.

## On the AI-assisted method

Nineteen tracked artifacts: six rejected, seven edited. The rejections included a
hallucinated Spring annotation, a retry loop that could not work because a constraint
violation marks a transaction rollback-only, and a confidently false comment about routing.
All three read as competent, idiomatic code.

Generation was never the bottleneck. The bottleneck was knowing which output to throw
away — which is why the [execution log](ai-execution-log.md) records dispositions and
reasoning rather than prompt transcripts, and why the quality gates are enforced in CI
rather than left to attention.
