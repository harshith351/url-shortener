# Testing approach, limitations and trade-offs

## What is tested, and why there

The suite is deliberately lopsided. Effort went where a defect would be both likely and
expensive, not where coverage is easy to accumulate.

| Layer | What it proves | Cost |
|---|---|---|
| `UrlValidatorTest` | Every SSRF and scheme rule, against a **stubbed** resolver | Fast, no network |
| `ShortCodeGeneratorTest` | Code shape, alphabet coverage, alias and reserved-word rules | Fast |
| `LinkServiceTest` | Collision retry, idempotent replay, cache/DB interaction, expiry and delete semantics | Fast, mocked |
| `BufferedClickEventSinkTest` | Aggregation folding, UTC day boundaries, bounded drop, contained flush failure | Fast |
| `RedirectControllerTest` | Referrer reduction discards tokens and query strings | Fast |
| `AuthorizationPolicyTest` | All 13 (mode x owner x caller) combinations, enumerated | Fast |
| `LinkServiceAuthorizationTest` | Every administrative path actually consults the policy | Fast, mocked |
| `ApiKeyServiceTest` | Hashing, issuance uniqueness, revoked/invalid key handling | Fast |
| `LinkApiIT` | Schema matches mappings, upsert SQL is valid, routes do not shadow, end-to-end flows | Slow, needs Docker |

Two choices in that table are worth defending.

**DNS is stubbed in the security tests.** These are the assertions that must never silently
weaken, so they cannot depend on what real DNS says today, on network egress, or on a
particular hostname continuing to resolve to a particular address. `HostResolver` exists as
an interface solely to make that possible — an abstraction introduced for testability, and
the reason is worth stating rather than leaving it to look like over-engineering.

**The integration test disables the private-network guard.** With it enabled, every test
URL would require real DNS resolution. Since the SSRF rules are covered exhaustively and
deterministically at the unit level, the integration test uses the setting that makes it
hermetic and focuses on what only it can prove.

**Integration tests use real Postgres and Redis, not fakes.** H2 with a Postgres dialect
would not execute `ON CONFLICT`, would not validate the Flyway migration, and would not
catch a mismatch between the migration and the JPA mappings. Those are exactly the defects
that reach production, so substituting the datastore would remove the test's entire reason
for existing.

## What was actually run

Maven Central was unreachable in the environment where this was authored, so **`mvn verify`
has not been executed**. This is stated in the README as well; it should not be discovered
by a reviewer running the build.

To avoid shipping wholly unexercised logic, the pure-Java cores — code generation, the SSRF
address rules, referrer reduction, cache TTL clamping, click aggregation, client-IP
extraction, route-shape matching, the authorisation truth table, API-key hashing — were extracted verbatim into a standalone program and
run against the same cases the JUnit suite asserts. 45,104 assertions pass.

That exercise found one real defect. A comment in `RedirectController` claimed the route
pattern prevented `/links` from being captured by the redirect mapping. It does not:
`/links` is a single segment and matches the code shape, so Spring routes `GET /links` to
the redirect handler. The behaviour is safe — but only because the reserved-word list
forbids allocating that code, not because of the pattern. The comment was corrected to say
what actually provides the guarantee, since a future maintainer trusting the wrong
explanation could remove the reserved list and open a real hole.

The harness proves logic, not wiring. It cannot tell you whether the Spring context starts,
whether the schema matches the mappings, or whether the upsert is valid SQL.

## Gaps, in order of how much they matter

**1. Incomplete auth rollout.** Ownership exists (see
[ADR-0005](adr/0005-link-ownership-and-authentication.md)), but the service ships in
`OPTIONAL` mode with unowned legacy links grandfathered. **Backfill tooling does not
exist**, and `REQUIRED` is unsafe until it does. Related: idempotency keys are still
globally unique, so cross-owner replays are refused rather than properly scoped. Also
missing: key rotation, key expiry, and read-only scopes.

**2. DNS rebinding.** Validation happens at creation time; a hostile domain can be
repointed afterwards. See [ADR-0004](adr/0004-ssrf-and-abuse-controls.md).

**3. No malicious-URL screening.** No Safe Browsing integration, no blocklist, no review
step for newly registered domains. A shortener wearing a recognisable brand needs this.

**4. At-most-once click delivery.** A crash loses up to one flush interval of clicks. Fine
for informational counts; not fine if the numbers ever become billing-grade.

**5. Fixed-window rate limiting** admits up to 2× the limit across a boundary, and trusts
`X-Forwarded-For`. That header is only meaningful behind a proxy that overwrites it — if
this service is ever exposed directly, any client can forge unlimited identities and the
limiter becomes decorative.

**6. Unbounded stats queries.** `dailyCounts` is capped at 365 days by parameter
validation, but a link with many distinct referrers aggregates over every row for that code
on each request. At high cardinality this wants a materialised total or a bounded window.

**7. Single region, single writer.** No read replicas, no geo-distribution. Multi-region
would need a replicated cache and a decision about code allocation across regions.

**8. No backfill tooling** for `owner_key_id`, which the auth rollout depends on.

**9. No load testing.** Latency and throughput claims in these documents are reasoned from
the design, not measured. A k6 or Gatling profile against the redirect path — with and
without Redis — is the obvious next validation step, and would be the honest way to
substantiate the "protect the hot path" argument that shapes the whole service.

## Trade-offs made knowingly

| Chosen | Rejected | Why |
|---|---|---|
| CSPRNG codes | Sequential base62 | Enumerability and volume disclosure |
| 302 + `no-store` | 301 | Countability, expiry, revocation |
| Async pre-aggregated clicks | Synchronous counter | Row contention and write amplification on the hot path |
| Soft delete | Hard delete | A reissued code makes an old printed link resolve somewhere new |
| Cache fails open | Fails closed | Redis is an accelerator, not a correctness dependency |
| Rate limiter fails open | Fails closed | It protects capacity, not authorisation |
| URL validation fails closed | Fails open | Here the failure mode is the organisation's domain used as cover |
| In-process queue | Kafka | Not justified while counts are informational; interface keeps the swap to one class |
| Real datastores in tests | H2 / embedded Redis | Substitutes cannot catch schema or SQL-dialect defects |
| SHA-256 for API keys | bcrypt / argon2 | Keys are 256-bit CSPRNG draws; a KDF defends against an attack that cannot happen |
| Three-state auth rollout | One flag-day deploy | A single enforcing deploy breaks every existing client at once |
| Authorisation as one pure function | Checks in each controller | The whole truth table must be readable in one place |
