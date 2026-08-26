package com.url.shortener.analytics;

import java.time.Instant;

/**
 * A single redirect, captured off the request thread.
 *
 * <p>Deliberately carries no IP address, user agent string, or any other identifier
 * that could re-identify a visitor. Only the referring host is kept.
 * See docs/adr/0003-analytics-pipeline.md.
 */
public record ClickEvent(String code, Instant occurredAt, String referrerHost) {
}
