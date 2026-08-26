package com.url.shortener.service;

import com.url.shortener.analytics.BufferedClickEventSink;
import com.url.shortener.analytics.ClickAggregate;
import com.url.shortener.analytics.ClickEvent;
import com.url.shortener.config.AppProperties;
import com.url.shortener.config.AuthMode;
import com.url.shortener.repo.ClickRollupWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BufferedClickEventSinkTest {

    private static final Instant NOON = Instant.parse("2026-03-01T12:00:00Z");

    private static AppProperties properties(int capacity) {
        return new AppProperties(
                "https://sho.rt",
                new AppProperties.Code(7, 3, Set.of()),
                new AppProperties.Cache(Duration.ofMinutes(10), Duration.ofSeconds(30)),
                new AppProperties.RateLimit(true, 60, 600),
                new AppProperties.Analytics(capacity, Duration.ofSeconds(2), 500),
                new AppProperties.Security(true, 2048),
                new AppProperties.Auth(AuthMode.DISABLED, "admin-token"));
    }

    @Test
    @DisplayName("folds repeated clicks into a single counted row per bucket")
    void aggregatesByCodeDayAndReferrer() {
        List<ClickAggregate> result = BufferedClickEventSink.aggregate(List.of(
                new ClickEvent("abc1234", NOON, "news.example"),
                new ClickEvent("abc1234", NOON.plusSeconds(30), "news.example"),
                new ClickEvent("abc1234", NOON, ""),
                new ClickEvent("xyz9999", NOON, "news.example")));

        assertThat(result).hasSize(3);
        assertThat(result).contains(
                new ClickAggregate("abc1234", LocalDate.of(2026, 3, 1), "news.example", 2),
                new ClickAggregate("abc1234", LocalDate.of(2026, 3, 1), "", 1),
                new ClickAggregate("xyz9999", LocalDate.of(2026, 3, 1), "news.example", 1));
    }

    @Test
    @DisplayName("day buckets are cut at UTC midnight, not local midnight")
    void splitsAcrossUtcDayBoundary() {
        List<ClickAggregate> result = BufferedClickEventSink.aggregate(List.of(
                new ClickEvent("abc1234", Instant.parse("2026-03-01T23:59:59Z"), ""),
                new ClickEvent("abc1234", Instant.parse("2026-03-02T00:00:01Z"), "")));

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("drops rather than blocks when the buffer is full")
    void dropsEventsWhenSaturated() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ClickRollupWriter writer = mock(ClickRollupWriter.class);
        BufferedClickEventSink sink = new BufferedClickEventSink(properties(2), writer, meters);

        for (int i = 0; i < 10; i++) {
            sink.accept(new ClickEvent("abc1234", NOON, ""));
        }

        assertThat(meters.counter("shortener.clicks.accepted").count()).isEqualTo(2.0);
        assertThat(meters.counter("shortener.clicks.dropped").count()).isEqualTo(8.0);
    }

    @Test
    void flushWritesAggregatedBatch() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ClickRollupWriter writer = mock(ClickRollupWriter.class);
        BufferedClickEventSink sink = new BufferedClickEventSink(properties(100), writer, meters);

        sink.accept(new ClickEvent("abc1234", NOON, "news.example"));
        sink.accept(new ClickEvent("abc1234", NOON, "news.example"));
        sink.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ClickAggregate>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).upsertAll(captor.capture());
        assertThat(captor.getValue())
                .containsExactly(new ClickAggregate("abc1234", LocalDate.of(2026, 3, 1), "news.example", 2));
    }

    @Test
    void flushOnAnEmptyQueueDoesNothing() {
        ClickRollupWriter writer = mock(ClickRollupWriter.class);
        new BufferedClickEventSink(properties(100), writer, new SimpleMeterRegistry()).flush();
        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("a failing flush is counted and swallowed, never rethrown into the scheduler")
    void flushFailureIsContained() {
        MeterRegistry meters = new SimpleMeterRegistry();
        ClickRollupWriter writer = mock(ClickRollupWriter.class);
        doThrow(new IllegalStateException("database down")).when(writer).upsertAll(anyList());
        BufferedClickEventSink sink = new BufferedClickEventSink(properties(100), writer, meters);
        sink.accept(new ClickEvent("abc1234", NOON, ""));

        assertThatCode(sink::flush).doesNotThrowAnyException();
        assertThat(meters.counter("shortener.clicks.flush.failures").count()).isEqualTo(1.0);
    }
}
