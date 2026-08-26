# ADR-0004 — SSRF and abuse controls

**Status:** Accepted

## Context

A URL shortener is an attractive intermediary. It takes an attacker-controlled destination
and dresses it in the host organisation's domain — which is precisely what makes a phishing
link credible, and what makes a naive shortener a liability rather than a convenience.

Three distinct abuses have to be closed.

## Decision 1: only http and https

`javascript:`, `data:` and `file:` URLs are rejected outright. A `javascript:` target
turns a shortened link into script execution in the context of whoever follows it; a
`data:` URL is an entire attacker-controlled document served from a trusted-looking link.

## Decision 2: resolved addresses must be publicly routable

Hosts are resolved and **every** returned address is checked. A short link pointing at
`169.254.169.254` turns any employee's browser into a probe of cloud instance metadata;
one pointing at `10.x` or `192.168.x` turns it into an internal port scanner.

`InetAddress.isSiteLocalAddress()` alone is not enough — it misses carrier-grade NAT
(`100.64.0.0/10`), `192.0.0.0/24`, and the benchmarking range `198.18.0.0/15`. Those are
checked explicitly.

**This fails closed.** An unresolvable host is rejected rather than accepted on trust; a
link nobody can resolve has no value, so strictness costs nothing. And a hostname that
resolves to a *mixture* of public and private addresses is rejected entirely.

The rejection message is deliberately vague ("not publicly routable"). Confirming which
internal ranges exist would itself be a disclosure.

The guard is switchable (`BLOCK_PRIVATE_NETWORKS=false`) purely so developers can shorten
`http://localhost:3000`. It must never be false in a deployed environment.

## Decision 3: no credentials in URLs

`https://accounts.example.com@evil.test/login` reads to a human as a link to
`accounts.example.com`. It is not. Userinfo is rejected.

Self-referential links are also refused, which prevents redirect loops between codes.

## Rate limiting

Per-IP fixed windows in Redis, with **separate budgets** for creation (60/min) and
redirection (600/min). One shared limit would either throttle legitimate traffic or fail
to constrain bulk link creation, because the two operations have nothing in common.

A fixed window admits up to 2× the limit across a boundary. That is acceptable for abuse
damping and costs one `INCR` per request; a sliding log or token bucket in Lua is the
upgrade if precision starts to matter.

**The limiter fails open.** Rate limiting here protects capacity — it is not an
authorisation control — so a limiter outage must not become a service outage. Where a
limiter *is* the security boundary, the correct choice is the opposite, and that
distinction is worth being explicit about rather than treating fail-open as a default.

## Known limitation: DNS rebinding

Resolution happens at creation time. An attacker who controls a domain can pass validation
with a public address and then repoint the record at `10.0.0.1`. Closing this requires
validating again at redirect time — expensive on the hot path — or forcing all outbound
traffic through an egress proxy that enforces the same rules. Neither is implemented here.
This is recorded rather than quietly ignored; see
[testing-and-trade-offs](../testing-and-tradeoffs.md).

## What this does not do

There is no malware or phishing blocklist (no Google Safe Browsing integration), and no
review workflow for links to newly registered domains. A production deployment carrying
a recognisable brand needs both.
