# Scenario 1 — Greenfield: build the core shortener

**Requirement as received:** "Build a URL shortener service from scratch with core APIs,
analytics, and reliability features."

## 1. Requirement understanding

The sentence is short and the system it describes is not. Normalising it into an
engineering problem meant first noticing that the two main operations have opposite
characteristics:

| | Create | Redirect |
|---|---|---|
| Volume | Low | Very high |
| Latency tolerance | Hundreds of ms | Tens of ms |
| Consistency need | Strong — a code must be unique | Read-your-writes, eventually |
| Failure cost | Retry | A broken link, possibly already printed |

That asymmetry is the whole design. Every subsequent decision reduces to *protect the
redirect path, accept cost on the create path*.

Three things the requirement does not say, resolved as stated assumptions:

- **"Reliability features" is unbounded.** Scoped to: graceful degradation when a
  dependency is down, rate limiting, idempotent creation, and health/metrics. Explicitly
  out of scope: multi-region, autoscaling, DR.
- **No mention of abuse.** A shortener dresses an attacker's destination in the host
  organisation's domain. Treated as a first-class requirement, not an extra.
- **"Analytics" is ambiguous** — handled separately in [Scenario 3](03-ambiguous.md).

## 2. Task decomposition

Sequenced by dependency; each task has an acceptance criterion that is checkable, not
aspirational.

| # | Task | Depends on | Done when |
|---|---|---|---|
| 1 | Schema + Flyway migration (`link`) | — | Migration applies; unique index on `code` exists |
| 2 | Config as typed properties | — | Every tunable reachable by env var, none hardcoded |
| 3 | Short code generation | 2 | Codes are base62, right length, non-enumerable; alias rules enforced |
| 4 | URL validation / SSRF guard | 2 | Private, loopback, CGNAT and metadata addresses refused; only http(s) |
| 5 | Create endpoint | 1,3,4 | 201 + Location; collisions retried; alias conflict is 409 |
| 6 | Redirect endpoint | 1 | 302 + no-store; 404 unknown; 410 retired |
| 7 | Redis cache-aside | 6 | Hit avoids DB; miss populates; negative caching present; degrades when Redis is down |
| 8 | Expiry | 1,6 | Expired link refuses at read time, independent of any background job |
| 9 | Idempotent creation | 5 | Replayed key returns the original link, never a second code |
| 10 | Rate limiting | 2,7 | Separate create/redirect budgets; fails open |
| 11 | Error contract + handler | 5,6 | Uniform envelope; internals never echoed |
| 12 | Observability | all | Health, Prometheus metrics, OpenAPI |

Tasks 3, 4 and 7 were identified up front as the ones where a defect would be expensive
and invisible, and were given disproportionate test effort.

## 3. Execution

The sequencing above was followed. Decisions of consequence were recorded as ADRs at the
moment they were made, not reconstructed afterwards:

- [ADR-0001](../adr/0001-short-code-generation.md) — CSPRNG codes over sequential IDs
- [ADR-0002](../adr/0002-redirect-semantics-and-caching.md) — 302 over 301; cache-aside with negative caching
- [ADR-0004](../adr/0004-ssrf-and-abuse-controls.md) — SSRF and abuse controls

Two implementation details are worth surfacing because they are non-obvious and would be
easy for a reviewer to read as accidental:

**`LinkWriter` exists as a separate bean for a reason.** A unique-constraint violation
marks the surrounding transaction rollback-only, so a collision-retry loop *inside* a
transaction cannot recover — every subsequent statement fails. Each insert attempt needs
`REQUIRES_NEW`, which means crossing a proxy boundary into another bean. The split looks
like ceremony and is load-bearing.

**`HostResolver` is an interface over `InetAddress.getAllByName`.** Introduced purely so
SSRF rules can be tested against a stub. Security assertions must not depend on what real
DNS says today, on network egress, or on a hostname continuing to resolve to a particular
address.

## 4. Validation

- Unit tests on the three high-risk components, with DNS stubbed.
- `LinkApiIT` against real Postgres and Redis via Testcontainers — H2 would not execute
  `ON CONFLICT`, would not validate the migration, and would not catch a mapping mismatch.
- Degradation checked by reasoning through each dependency failure and encoding the
  intended behaviour in code (`LinkCache` and `RateLimitFilter` both fail open, both
  counted by metrics).

**Caveat, stated rather than buried:** `mvn verify` has not been executed — Maven Central
was unreachable in the authoring environment. The algorithmic cores were extracted verbatim
into a dependency-free harness and run against the same cases the JUnit suite asserts
(45,104 assertions pass). See [testing and trade-offs](../testing-and-tradeoffs.md).

## 5. Outcome

A runnable service with create, redirect, expiry, idempotency, caching, rate limiting,
SSRF protection, metrics and OpenAPI. The largest known gap at the end of this phase —
no notion of a link owner — became [Scenario 2](02-brownfield.md).
