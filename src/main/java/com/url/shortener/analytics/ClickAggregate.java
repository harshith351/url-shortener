package com.url.shortener.analytics;

import java.time.LocalDate;

/** Click events folded into one counter per (code, UTC day, referrer host). */
public record ClickAggregate(String code, LocalDate clickDay, String referrerHost, long clicks) {
}
