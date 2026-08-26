# Scenario 3 — Ambiguous: "users should be able to see how their links are performing"

**Requirement as received, verbatim.** One sentence. It is the kind of requirement that
looks actionable and is not, because every noun in it is underspecified.

## 1. Why this is ambiguous

Six independent questions, each with materially different engineering consequences:

| Ambiguity | Cheap reading | Expensive reading |
|---|---|---|
| **"performing"** | A click count | Unique visitors, geography, device, conversion |
| **"see"** | An API endpoint | A dashboard, scheduled reports, CSV export |
| **"their"** | Any link, unauthenticated | Requires ownership, which did not yet exist |
| Freshness | Minutes behind | Real-time |
| Retention | Forever | A defined window with deletion |
| Granularity | Per day | Per hour, per click |

The gap between the two columns is roughly an afternoon versus a quarter. Guessing which
one is meant, and guessing wrong, is the expensive failure — either shipping a counter when
a dashboard was wanted, or building an event pipeline when a number would have done.

## 2. Questions raised

Recorded as questions to the requester, with the assumption applied in their absence. The
point is not that assumptions were made; it is that they are written down and falsifiable.

| Question | Assumption applied | Cost if wrong |
|---|---|---|
| Are these numbers ever billed against, or reported in an SLA? | No — informational | High. Billing-grade needs at-least-once delivery and a durable log |
| Is minutes-fresh acceptable, or must a click appear immediately? | Minutes | Moderate. Real-time means a streaming read path |
| Do we need unique visitors, or total clicks? | Total clicks | **High** — see below |
| Geography and device breakdown? | No | Moderate; needs IP geolocation and UA parsing |
| How long is history kept? | Indefinitely, aggregated | Low; rollups are small |
| Who may see a link's statistics? | Anyone (at the time) | High — resolved later in [Scenario 2](02-brownfield.md) |

**"Unique visitors" is the question that decides the architecture.** Total clicks can be a
counter. Uniques require identifying returning visitors — a cookie, an IP hash, or a
probabilistic structure like HyperLogLog — and each of those is a privacy decision with
legal weight, not merely a technical one. Answering it wrongly in either direction is
costly: build it and you have created personal data you must now govern; skip it and the
feature may not answer the question anyone actually had.

The assumption applied was *total clicks, no per-visitor identity*, precisely because it is
the reversible choice. Adding uniques later is additive. Un-collecting visitor identifiers
already gathered is not.

## 3. Normalised requirement

> Link owners can retrieve, per link: total clicks, a daily time series over a
> caller-specified window, and the top referring hosts. Counts are eventually consistent
> within seconds, and the response states its own staleness. No per-visitor data is
> collected or stored.

Now testable, now estimable, and the deliberate exclusions are visible rather than implied.

## 4. Decomposition and execution

| # | Task | Done when |
|---|---|---|
| 1 | Decide the storage shape | Pre-aggregated rollup, no raw event table |
| 2 | Get counting off the hot path | Redirect latency unaffected by analytics being slow or down |
| 3 | Rollup table + concurrent-safe write | Two instances flushing the same bucket converge |
| 4 | Stats endpoint | Totals, daily series, top referrers, bounded parameters |
| 5 | Make eventual consistency explicit | The contract is in the payload, not the docs |

The design that followed is in [ADR-0003](../adr/0003-analytics-pipeline.md). The obvious
implementation — `UPDATE link SET clicks = clicks + 1` during the redirect — was rejected
for three reasons at once: it puts a row lock on the hottest read path, it couples redirect
availability to analytics availability, and it produces one write per click for data only
ever read in aggregate.

Two consequences of the ambiguity resolution are visible directly in the code:

- **`freshnessSeconds` in the stats response.** The "how fresh?" question was never
  authoritatively answered, so rather than pick a number and hope, the service states its
  own staleness in every payload. Callers can then decide whether it is good enough — and
  if it is not, that is a conversation with evidence rather than a bug report.
- **Only the referring *host* is stored, never the full referrer URL.** Referrer query
  strings routinely carry session tokens and email addresses. The requirement said nothing
  about this; collecting it by accident would have been the default.

## 5. What changed when the ambiguity was later resolved

The word "their" was eventually answered — by [Scenario 2](02-brownfield.md), which added
ownership. That retroactively changed the privacy analysis: click data attributable to an
identified owner is a different category of data from anonymous aggregates.

This is the honest outcome of an ambiguous requirement, and worth stating plainly: the
assumption was recorded, the assumption later turned out to be wrong, and because it had
been written down the consequence was traceable rather than a surprise.

## 6. Still open

- Whether uniques are needed. Deliberately unanswered; would change the privacy posture.
- Retention policy. Currently indefinite, which is a decision nobody has actually made.
- Dashboard or export. No UI exists; "see" was implemented as an API.
