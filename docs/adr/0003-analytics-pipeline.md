# ADR-0003 — Analytics pipeline

**Status:** Accepted

## Context

Link owners want to know how their links are performing. The obvious implementation —
`UPDATE link SET clicks = clicks + 1` during the redirect — is wrong in three ways at once:

- It puts a write, and a row lock, on the hottest read path in the system. Every click on
  a popular link contends for the same row.
- It couples redirect availability to analytics availability. A slow write means a slow
  redirect; a failed write means a failed redirect.
- It produces one write per click, which is the worst possible write amplification for
  data that is only ever read in aggregate.

## Decision

Clicks are recorded **off** the request path and stored **pre-aggregated**.

```
redirect ──▶ ClickEventSink.accept(event)   [bounded queue, non-blocking, never throws]
                       │
                  every 2s, up to 5000 events
                       ▼
             fold to (code, UTC day, referrer host) ──▶ batched ON CONFLICT upsert
```

**Bounded, not blocking.** When the queue is full, events are dropped and counted rather
than applying back-pressure to redirects. Losing analytics is cheaper than degrading the
product's primary function. `shortener_clicks_dropped_total` is the alert.

**Aggregate before writing.** Ten thousand clicks on one link in one interval become one
row update, not ten thousand. The upsert increments in SQL
(`clicks = link_click_daily.clicks + EXCLUDED.clicks`), so concurrent flushes from
multiple instances converge without locking or read-modify-write races — the database
performs the addition, not the application.

**A failed batch is dropped, not requeued.** A poison batch that is retried forever
starves every fresh event behind it. The loss is bounded, counted and logged.

## Privacy

No IP address, user agent, cookie or click-level row is stored. The only dimension kept
beyond the code and the day is the referring **host** — never the full referrer URL, whose
query string routinely carries session tokens, email addresses and internal identifiers.
There is nothing in `link_click_daily` that can re-identify a visitor, which keeps the
table outside most of the retention and subject-access machinery that would otherwise
apply. That is a deliberate trade: no per-visitor funnel analysis is possible, and adding
it later is a decision with legal weight, not just an engineering task.

## Consequences

Counts are **eventually consistent** — lagging by up to one flush interval. Rather than
leave callers to discover this, the stats response carries `freshnessSeconds` so the
contract is explicit in the payload.

Delivery is **at-most-once**: a hard crash loses whatever is buffered, bounded by the flush
interval. For informational click counts this is the right trade against the cost of a
durable log. If these numbers ever become billing-grade or SLA-reported, that changes, and
the upgrade path is deliberately narrow: `ClickEventSink` is an interface with one method,
and replacing the in-process implementation with a Kafka producer touches exactly one class.

Day buckets are cut at **UTC** midnight, not the viewer's or the owner's local time, and
are labelled as such. A single unambiguous definition beats a locally intuitive one that
silently disagrees with itself across time zones.
