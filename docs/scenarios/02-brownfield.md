# Scenario 2 — Brownfield: add link ownership to a live service

**Requirement as received:** "Anyone can currently read any link's statistics and delete
any link. Add ownership so a link can only be administered by whoever created it."

This is the genuinely brownfield case: the service already exists, already has clients,
and already has rows in the `link` table with no owner. The interesting work is not
writing an authorisation check — it is landing one without breaking anything.

## 1. Codebase reasoning — impact analysis before any code

Working outward from the requirement:

| Layer | Component | Impact |
|---|---|---|
| Schema | `link` | New nullable `owner_key_id` |
| Schema | *(new)* `api_key` | New table |
| Domain | `Link` | New field; constructor signature changes |
| Domain | *(new)* `ApiKey` | New entity |
| Service | `LinkService` | `create`, `stats`, `delete` all gain a caller identity |
| Service | *(new)* `ApiKeyService` | Issue, hash, resolve |
| Security | *(new)* `AuthorizationPolicy` | The decision function |
| Web | `LinkController` | Reads `X-API-Key`, resolves it to an owner id |
| Web | *(new)* `ApiKeyController` | Provisioning |
| Web | `GlobalExceptionHandler` | Two new fault types → 401, 403 |
| Config | `AppProperties` | New `auth` block |
| **Not touched** | `RedirectController`, `LinkCache`, analytics | — |

**The last row is the most important one.** Redirects are not authenticated in any mode:
ownership governs administration of a link, not the public act of following it. That keeps
credential lookups entirely off the hot path, and means an outage of the `api_key` table
cannot stop links from resolving. Establishing that boundary first is what kept the change
small.

Two ripples the analysis predicted, which shaped the implementation:

- **Every construction site of `AppProperties` and `Link` would break.** Records and
  constructors are used by tests. Handled by adding overloads that delegate — the 5-arg
  `Link` constructor and 4-arg `CreateCommand` are retained and create unowned instances —
  so no existing caller or test needed rewriting to compile. Backward-compatible API
  evolution inside the codebase, for the same reason it matters across a network boundary.
- **`ADR-0003`'s privacy argument partly rested on there being no identified owner.** Once
  click data is attributable to one, it becomes a different category of data. Noted in
  ADR-0005 rather than left to be rediscovered.

## 2. A defect found by the analysis, not by a test

`idempotency_key` carries a **service-wide** unique index, inherited from a schema written
when the service was implicitly single-tenant.

Once links have owners, that is an information leak. A caller POSTs with
`Idempotency-Key: order-1`, the service finds an existing link with that key, and returns
it — including its target URL — regardless of who created it. Keys like `order-1`,
`invoice-2` and `user-42` are guessable by construction.

No existing test would have caught this. It was found by asking "what else does an owner
column change?" rather than by running the suite.

- **Immediate fix (shipped):** a replay whose matched link has a different owner is refused
  with `409 idempotency_key_conflict` rather than returned.
- **Proper fix (deferred, deliberately):** scope uniqueness to
  `(owner_key_id, idempotency_key)`. This cannot land yet — NULLs do not compare equal in a
  Postgres unique index, so while unowned rows exist the constraint would silently stop
  enforcing idempotency for them. It is blocked on the backfill, and that dependency is
  recorded in ADR-0005 so the deferral is a decision rather than an oversight.

## 3. Migration strategy

```sql
CREATE TABLE api_key (...);
ALTER TABLE link ADD COLUMN owner_key_id BIGINT NULL REFERENCES api_key (id);
```

Both statements are additive. Adding a **nullable** column with **no default** is a
catalogue-only change in Postgres 11+ — no table rewrite, no long lock on the hottest table
in the system. A `NOT NULL` column with a backfilled default would have been a completely
different operation, and the difference is worth stating in the migration itself, where the
next person will read it.

## 4. Rollout — expand, migrate, contract

A single deploy that started rejecting unauthenticated requests would have broken every
client at once. `AuthMode` makes the transition three independently deployable, independently
reversible steps:

| Step | Mode | Behaviour | Reversible by |
|---|---|---|---|
| 1 | `DISABLED` | Schema exists, nothing enforced | Rollback |
| 2 | `OPTIONAL` | New links get owners; owned links protected; **unowned links stay open** | Config flip |
| 3 | `REQUIRED` | Key mandatory; unowned links refused | Config flip |

Step 2 is what makes the change non-breaking, and step 3 is what makes the ordering
mandatory: the same legacy link that is administrable in `OPTIONAL` is refused in
`REQUIRED`. **The backfill must complete before that flip**, and that is encoded in the
`AuthMode` javadoc and asserted in tests rather than left as tribal knowledge.

The service ships defaulting to `OPTIONAL`.

## 5. Execution and validation

Authorisation lives in one pure function, `AuthorizationPolicy`, with no dependency on
Spring, HTTP, the database or the clock. Access control scattered across controllers is the
code most costly to get subtly wrong and hardest to review; isolated, the entire truth
table can be read at once.

Validation was layered because the two realistic failure modes are different:

1. **`AuthorizationPolicyTest`** enumerates all 13 (mode × owner × caller) combinations —
   exhaustive, not sampled, because a missed case here is a breach rather than a bug.
   Includes ids above 127, since `Long` caching would let a reference comparison pass
   accidentally on small values.
2. **`LinkServiceAuthorizationTest`** proves each administrative path actually *calls* the
   policy. A correct policy that some endpoint forgets to consult is the realistic defect.
3. **`LinkApiIT`** proves it end to end over HTTP: owner 200, stranger 403, anonymous 401,
   invalid key 401, cross-owner idempotency replay 409, and — the regression that matters
   most — an owned link still redirects for everyone.
4. **Harness** re-runs the truth table and key hashing outside the Spring build.

**Human sign-off gate.** This change alters access control and modifies a live schema.
Under the working agreement in the [AI execution log](../ai-execution-log.md), both the
migration and `AuthorizationPolicy` were classed high-impact: reviewed line by line, and
the rollout sequence chosen by the engineer, not accepted as generated.

## 6. What was deliberately not done

- Key rotation and expiry (keys are revocable, but never expire).
- Scopes — a key is all-or-nothing over its links; no read-only key.
- Per-key rate limiting; limits remain per-IP.
- Backfill tooling. The rollout depends on it, so this is the top of the follow-up list,
  not a nice-to-have.
