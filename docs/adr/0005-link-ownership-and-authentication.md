# ADR-0005 — Link ownership and authentication

**Status:** Accepted (supersedes the "no authentication" position in ADR-0003)

## Context

The service shipped with no notion of a link owner. Anyone could read any link's
statistics and retire any link. That was acceptable for a prototype and is not acceptable
for anything with more than one user — and it also constrained the analytics design, since
ADR-0003's privacy argument partly rested on there being no identified owner to attribute
click data to.

The change had to be made to a service that already had clients and existing rows.

## Decision 1: API keys, not user accounts

A key is a 256-bit CSPRNG draw, prefixed `sk_`, returned exactly once at issue time. Only
its SHA-256 is stored, so a database disclosure yields no working credentials.

**Why not bcrypt or argon2.** Adaptive hashes exist to make brute force expensive against
low-entropy, human-chosen passwords. These keys have 256 bits of entropy: there is no
dictionary and no feasible search. A slow KDF would add latency to every authenticated
request to defend against an attack that is already impossible, and would prevent the
lookup from using an index. *If keys ever become user-chosen, this reasoning collapses and
the algorithm must change* — which is why the argument is recorded rather than the
conclusion alone.

Verification is a lookup **by hash** against a unique index, so the comparison happens in
the database and no timing-sensitive comparison runs in application code. The one place
that genuinely needs `MessageDigest.isEqual` is the admin bootstrap token, which is
compared directly.

## Decision 2: a three-state rollout, not a flag day

`AuthMode` is `DISABLED → OPTIONAL → REQUIRED`, deployed in that order.

A single release that began rejecting unauthenticated requests would have broken every
existing client simultaneously. Instead: ship the schema inert, turn on enforcement for
newly owned links while grandfathering old ones, backfill, then contract. Each step is
independently deployable and independently reversible.

`OPTIONAL` is the interesting state, and the whole reason the change is safe: a link with
`owner_key_id IS NULL` remains administrable by anyone, so clients migrate at their own
pace. In `REQUIRED` the same link is refused — which is precisely why the backfill must
complete first, and why that ordering is written down here rather than living in someone's
head.

## Decision 3: redirects are never authenticated

Ownership governs administration of a link, not the public act of following it. This keeps
the hot path completely free of credential lookups — authentication cost falls only on
create, stats and delete, which are low-volume. It also means an outage of the `api_key`
table cannot stop links from resolving.

## Decision 4: authorisation as one pure function

Every access decision lives in `AuthorizationPolicy`, which has no dependency on Spring,
HTTP, the database or the clock. Access control scattered across controllers is the code
most costly to get subtly wrong and hardest to review. Isolated, the entire truth table —
13 combinations of mode, owner and caller — is readable at once and enumerated
exhaustively in tests.

The service-level tests then prove something the policy tests cannot: that every
administrative path actually *calls* the policy. A correct policy that some endpoint
forgets to consult is the realistic failure mode.

## Consequence: an idempotency leak, found and closed

Impact analysis surfaced a defect that was latent in the original design. `idempotency_key`
is unique **service-wide**, a consequence of the schema having been single-tenant. Once
links have owners, a caller could POST with `Idempotency-Key: order-1` and receive whichever
link another tenant happened to create with that key — reading a URL they were never
entitled to see.

The immediate fix is to refuse the replay with `409 idempotency_key_conflict` when the
matched link has a different owner. The proper fix is to scope uniqueness to
`(owner_key_id, idempotency_key)`, which is deferred: it needs a migration, and NULL
owners do not compare equal in a Postgres unique index, so it cannot land until the
backfill is finished. Recorded here so the deferral is a decision rather than an oversight.

## Known gaps

- **No key rotation or expiry.** Keys are revocable (`active = false`) but never expire.
- **No scopes.** A key is all-or-nothing over the links it owns; there is no read-only key.
- **No rate limiting per key.** Limits are still per-IP, so one key behind many addresses
  is unconstrained.
- **Bootstrap by shared token.** Adequate for provisioning, not a substitute for a real
  administrative identity.
