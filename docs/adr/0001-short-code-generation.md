# ADR-0001 — Short code generation

**Status:** Accepted

## Context

Every short link needs a code that is unique, short enough to be worth using, and safe to
hand to the public. Three approaches are standard.

## Options

**Sequential IDs encoded as base62.** Shortest possible codes, no collisions by
construction, trivially cheap. Two disqualifying problems: the entire corpus is
enumerable, so anyone can walk `/1`, `/2`, `/3` and read every link the organisation has
ever created; and monotonic codes disclose creation volume to any observer who creates two
links and measures the gap. For a service that will carry internal and pre-release links,
both are unacceptable.

**Hash of the target URL.** Deduplicates identical URLs for free. But it makes the code a
function of the destination, so an attacker who guesses a URL can confirm whether anyone
shortened it — a real leak for links containing tokens or unreleased paths. It also
prevents two users from having independent links to the same destination with independent
expiry and analytics.

**Random draw from a CSPRNG.** Not enumerable, discloses nothing, requires collision
handling.

## Decision

Seven characters drawn from `SecureRandom` over base62.

Collisions are resolved by the unique index on `link.code`: insert, catch the constraint
violation, redraw, bounded at five attempts. The database is the arbiter of uniqueness — a
`SELECT` before `INSERT` would still race under concurrency, and would cost an extra query
on every create for the privilege.

Custom aliases share the same shape (`[A-Za-z0-9_-]{3,16}`) and are checked against a
reserved list so no link can ever shadow an API route. Aliases are never retried on
collision: a different code is not what the caller asked for, so that is a `409`.

## Consequences

62^7 ≈ 3.5 × 10^12. At 100 million links the space is ~0.003% occupied and a collision on
any single draw is about one in thirty thousand; five attempts make failure negligible.
Exhausting five attempts is therefore a signal of genuine key-space pressure or a
persistent write fault, so it surfaces as `503`, not `500`.

`SecureRandom` costs more than a counter. On the create path, which is low-volume, this is
not measurable.

**Trigger to revisit:** sustained non-zero `code_generation_failed`, or link count
approaching 10^11. The remedy is raising `app.code.length`, which requires no migration —
the column already accommodates 16 characters and existing codes keep working.
