package com.url.shortener.analytics;

import com.url.shortener.config.AppProperties;
import com.url.shortener.repo.ClickRollupWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * In-process buffered sink: bounded queue on the write side, periodic batched upsert
 * on the flush side.
 *
 * <p>Trade-offs, made explicitly:
 * <ul>
 *   <li><b>Bounded, not blocking.</b> When the queue is full, events are dropped and
 *       counted rather than applying back-pressure to redirects. Losing analytics is
 *       cheaper than degrading the product's primary function.</li>
 *   <li><b>At-most-once.</b> A hard crash loses whatever is buffered (bounded by the
 *       flush interval). A durable log is the upgrade path when counts become
 *       billing-grade rather than informational.</li>
 *   <li><b>UTC day buckets.</b> Days are cut at UTC midnight, not the viewer's or the
 *       link owner's local time. Presented as UTC so the number is never ambiguous.</li>
 * </ul>
 */
@Component
public class BufferedClickEventSink implements ClickEventSink {

    private static final Logger log = LoggerFactory.getLogger(BufferedClickEventSink.class);

    private final BlockingQueue<ClickEvent> queue;
    private final ClickRollupWriter writer;
    private final int flushBatchSize;

    private final Counter accepted;
    private final Counter dropped;
    private final Counter flushed;
    private final Counter flushFailures;

    public BufferedClickEventSink(AppProperties properties,
                                  ClickRollupWriter writer,
                                  MeterRegistry meters) {
        this.queue = new ArrayBlockingQueue<>(properties.analytics().queueCapacity());
        this.writer = writer;
        this.flushBatchSize = properties.analytics().flushBatchSize();
        this.accepted = Counter.builder("shortener.clicks.accepted").register(meters);
        this.dropped = Counter.builder("shortener.clicks.dropped").register(meters);
        this.flushed = Counter.builder("shortener.clicks.flushed").register(meters);
        this.flushFailures = Counter.builder("shortener.clicks.flush.failures").register(meters);
        meters.gauge("shortener.clicks.queue.depth", this.queue, BlockingQueue::size);
    }

    @Override
    public void accept(ClickEvent event) {
        if (queue.offer(event)) {
            accepted.increment();
        } else {
            // Alert on this counter: sustained drops mean the flush cannot keep up.
            dropped.increment();
        }
    }

    @Scheduled(fixedDelayString = "${app.analytics.flush-interval}")
    public void flush() {
        List<ClickEvent> batch = new ArrayList<>(Math.min(flushBatchSize, 1024));
        queue.drainTo(batch, flushBatchSize);
        if (batch.isEmpty()) {
            return;
        }
        List<ClickAggregate> aggregates = aggregate(batch);
        try {
            writer.upsertAll(aggregates);
            flushed.increment(batch.size());
        } catch (RuntimeException ex) {
            // Deliberately not requeued: a poison batch would otherwise loop forever and
            // starve fresh events. The loss is bounded and counted.
            flushFailures.increment();
            log.warn("Dropping {} click events after failed rollup flush", batch.size(), ex);
        }
    }

    private record BucketKey(String code, LocalDate clickDay, String referrerHost) {
    }

    /** Folds N events into at most one row per (code, day, referrer) to cut write amplification. */
    public static List<ClickAggregate> aggregate(List<ClickEvent> events) {
        Map<BucketKey, Long> counts = new HashMap<>();
        for (ClickEvent event : events) {
            LocalDate day = event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
            counts.merge(new BucketKey(event.code(), day, event.referrerHost()), 1L, Long::sum);
        }
        List<ClickAggregate> result = new ArrayList<>(counts.size());
        counts.forEach((key, count) ->
                result.add(new ClickAggregate(key.code(), key.clickDay(), key.referrerHost(), count)));
        return result;
    }

    /** Best-effort drain on graceful shutdown. */
    @PreDestroy
    public void drainOnShutdown() {
        log.info("Flushing {} buffered click events before shutdown", queue.size());
        while (!queue.isEmpty()) {
            flush();
        }
    }
}
