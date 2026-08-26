# AI execution log

How AI was actually used to build this service, and what was done with what it produced.

The operating principle throughout: **AI assists within tasks; the engineer owns
correctness, maintainability and production readiness.** Nothing below is a claim that the
tooling was trustworthy. The value came from being fast at generation and from a review
discipline that assumed the output was wrong until checked.

---

## 1. Working method

Each unit of work was given to the model as a brief with four parts, because unstructured
prompts produce plausible code that solves an adjacent problem:

- **Intent** — what the change is for, in one sentence.
- **Constraints** — stack, existing patterns to match, things it must not do.
- **Acceptance criteria** — checkable conditions, not adjectives.
- **Technical context** — the surrounding code it must fit into.

A representative brief, for the SSRF guard:

> **Intent:** Prevent shortened links being used to reach internal infrastructure.
> **Constraints:** Java 21, Spring; no new dependencies; DNS resolution must be injectable
> so tests need no network.
> **Acceptance:** Rejects loopback, RFC 1918, link-local, any-local, multicast, CGNAT
> (100.64/10), 192.0.0.0/24 and 198.18/15; rejects non-http(s) schemes; rejects userinfo;
> rejects a host resolving to a *mixture* of public and private addresses; fails closed on
> resolution failure; error message must not disclose which internal ranges exist.
> **Context:** Called from `LinkService.create`; config comes from `AppProperties.Security`.

The `mixture` and `fails closed` clauses were added by the engineer after the first
generation handled neither. Sharpening the acceptance criteria — rather than re-prompting
with "make it more secure" — is what changed the output.

### Prompting patterns that worked

- **Adversarial follow-up.** After each component: *"what input breaks this?"* This produced
  the CGNAT and multi-address SSRF cases, and the `Long` reference-comparison case in
  authorisation.
- **Asking for the argument, not the artefact.** *"Give me the case for sequential IDs and
  the case against"* produced a usable ADR; *"generate short codes"* produced code with an
  unexamined choice baked in.
- **Impact analysis before code on brownfield work.** *"List every module this touches,
  including ones that only break at compile time"* produced the table in
  [Scenario 2](scenarios/02-brownfield.md) and directly surfaced the idempotency leak.

### Patterns that did not

- Asking for "production-ready" anything. The word adds confidence, not quality.
- Accepting test suites wholesale. Generated tests skew toward asserting what the code
  does, which passes and proves nothing.

---

## 2. Traceability

| # | Artefact | Disposition | Rationale |
|---|---|---|---|
| 1 | Stack, architecture shape, expand/migrate/contract rollout | **Engineer-owned** | Decided by the engineer; AI used to argue alternatives, not to choose |
| 2 | `UrlValidator` scheme/userinfo checks | Accepted | Matched brief; verified against 12 hostile inputs |
| 3 | `UrlValidator` private-range check | **Edited** | Generated version used `isSiteLocalAddress()` alone — misses CGNAT, 192.0.0.0/24, 198.18/15. Ranges added explicitly |
| 4 | SSRF behaviour on unresolvable host | **Edited** | Generated code allowed it through. Changed to fail closed |
| 5 | `HostResolver` interface | Accepted | Introduced so security tests need no DNS |
| 6 | `@ResponseStatusNoContent` on the delete handler | **Rejected** | **No such annotation exists.** Hallucinated; would not have compiled. Removed |
| 7 | Analytics as a synchronous counter update | **Rejected** | First proposal put a row lock on the hot path. Replaced with the buffered pipeline in ADR-0003 |
| 8 | `BufferedClickEventSink.aggregate` map key | **Edited** | Used a `ClickAggregate` with `clicks = 0` as the key — correct only by accident, since equality includes the count. Replaced with an explicit `BucketKey` |
| 9 | Retry-on-collision inside a single transaction | **Rejected** | Cannot work: a constraint violation marks the transaction rollback-only. Restructured into `LinkWriter` with `REQUIRES_NEW` |
| 10 | Duration config as `10m` | **Edited** | Changed to ISO-8601 `PT10M` for unambiguous binding |
| 11 | Expiry validation reusing `InvalidUrlException` | **Edited** | An expiry fault is not a URL fault. Split into `invalid_expiry` |
| 12 | `RateLimitExceededException` | **Rejected** | Generated but never referenced — the filter writes its own response. Dead code, deleted |
| 13 | Lombok | **Rejected** | Annotation-processing risk not worth saving getters |
| 14 | H2 for integration tests | **Rejected** | Cannot execute `ON CONFLICT`, cannot validate the migration — would remove the test's reason to exist |
| 15 | Comment claiming the route pattern protects `/links` | **Rejected** | **False.** `/links` is a single segment and matches the code shape. Corrected to credit the reserved-word list, which is what actually protects it |
| 16 | `AuthorizationPolicy` as a pure function | Accepted | Structure proposed by the engineer; implementation generated and reviewed line by line |
| 17 | Cross-owner idempotency leak | **Engineer-found** | Surfaced by impact analysis, not by any test. Fix and deferral in ADR-0005 |
| 18 | Plain SHA-256 for API keys | Accepted **with reasoning recorded** | Correct for 256-bit random keys, wrong for user-chosen ones. The *condition* is documented so the choice can be revisited when it stops holding |
| 19 | Generated unit tests | **Edited throughout** | First drafts asserted implementation. Rewritten to assert behaviour and hostile input |

Six rejections and seven edits out of nineteen tracked items. Items 6, 9 and 15 are the
ones worth dwelling on: a hallucinated API, a transactional impossibility, and a confidently
wrong comment. All three read as competent code.

---

## 3. Quality gates

Enforced in CI (`.github/workflows/ci.yml`), not left to discipline:

| Gate | Tool | Fails on |
|---|---|---|
| Compile + unit tests | Maven / JUnit 5 | Any failure |
| Integration tests | Failsafe + Testcontainers | Any failure |
| Dependency vulnerabilities | OWASP dependency-check | CVSS ≥ 7 |
| Static analysis | SpotBugs + FindSecBugs | Any finding |
| Coverage | JaCoCo | Published as an artefact |

Human sign-off was required, and given, for: the `AuthorizationPolicy` truth table, both
Flyway migrations, the SSRF rules, and the auth rollout sequence. These are the changes
where a plausible-looking error is a breach or an outage rather than a bug.

---

## 4. Secure AI usage

- No credentials, keys, connection strings or production data were placed in any prompt.
- The service's own bootstrap token has no default, so a misconfigured deployment fails
  closed rather than issuing credentials.
- Generated code was not trusted to be safe by virtue of looking safe: every security
  control has hostile-input tests, and the SSRF rules are tested against a stub so they
  cannot silently weaken with a DNS change.
- Nothing was accepted on the basis that it "looked like" idiomatic Spring. Items 6 and 9
  both looked entirely idiomatic.

---

## 5. Honest limits of this record

`mvn verify` has **not** been run — Maven Central was unreachable in the authoring
environment. Rather than present an unverified repository as working, the algorithmic cores
were extracted verbatim into a dependency-free harness and executed against the same cases
the JUnit suite asserts: **45,104 assertions, 0 failures** (`tools/verify/`).

That harness is what caught item 15. It proves logic, not wiring — it cannot tell you
whether the Spring context starts, whether the schema matches the mappings, or whether the
upsert is valid SQL. `LinkApiIT` exists for exactly that, and needs a real build and a
Docker daemon.

The single most useful thing in this log is not the acceleration. It is that the
disposition column has six "rejected" rows. Output volume was never the constraint;
knowing which output to throw away was.
