package com.url.shortener.analytics;

/**
 * The seam between the redirect hot path and analytics storage.
 *
 * <p>Implementations MUST NOT block the caller and MUST NOT throw: a failure to count
 * a click is never allowed to fail the redirect that the user actually asked for.
 * Swapping the in-process implementation for a Kafka producer is a change to exactly
 * one class.
 */
public interface ClickEventSink {

    void accept(ClickEvent event);
}
