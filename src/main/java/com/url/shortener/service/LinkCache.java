package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Cache-aside layer in front of the link table.
 *
 * <p>Three decisions are load-bearing here:
 * <ul>
 *   <li><b>Fail open.</b> Every Redis interaction is wrapped. If Redis is unreachable the
 *       service degrades to database-only reads and keeps serving; it does not fail. The
 *       cache is an accelerator, never a dependency for correctness.</li>
 *   <li><b>Negative caching.</b> A flood of requests for codes that do not exist would
 *       otherwise pass straight through to Postgres. Misses are cached too, with a much
 *       shorter TTL so a newly created code goes live promptly.</li>
 *   <li><b>TTL clamped to expiry.</b> A cached entry never outlives the link's own
 *       expiry, so an expired link cannot keep redirecting until the cache lapses.</li>
 * </ul>
 */
@Component
public class LinkCache {

    private static final Logger log = LoggerFactory.getLogger(LinkCache.class);

    private static final String KEY_PREFIX = "link:";
    /** Not a legal URL, so it can never collide with a real cached value. */
    private static final String MISSING_MARKER = "\u0000missing";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration negativeTtl;

    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public LinkCache(StringRedisTemplate redis, AppProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.ttl = properties.cache().ttl();
        this.negativeTtl = properties.cache().negativeTtl();
        this.hits = Counter.builder("shortener.cache.hits").register(meters);
        this.misses = Counter.builder("shortener.cache.misses").register(meters);
        this.errors = Counter.builder("shortener.cache.errors").register(meters);
    }

    /**
     * @return empty when the cache has no opinion; otherwise a result that either carries
     *         the target URL or records that the code is known not to exist
     */
    public Optional<Lookup> lookup(String code) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + code);
            if (value == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(MISSING_MARKER.equals(value)
                    ? Lookup.missing()
                    : Lookup.found(value));
        } catch (RuntimeException ex) {
            errors.increment();
            log.debug("Cache lookup failed for {}; falling through to the database", code, ex);
            return Optional.empty();
        }
    }

    public void putFound(String code, String longUrl, Instant expiresAt, Instant now) {
        Duration effective = clampToExpiry(ttl, expiresAt, now);
        if (effective.isZero() || effective.isNegative()) {
            return;
        }
        write(code, longUrl, effective);
    }

    public void putMissing(String code) {
        write(code, MISSING_MARKER, negativeTtl);
    }

    public void evict(String code) {
        try {
            redis.delete(KEY_PREFIX + code);
        } catch (RuntimeException ex) {
            errors.increment();
            // A stale entry can outlive a delete by at most the TTL. Callers that need a
            // hard guarantee must not rely on the cache for authorisation decisions.
            log.warn("Cache eviction failed for {}; entry will expire naturally", code, ex);
        }
    }

    private void write(String code, String value, Duration expiry) {
        try {
            redis.opsForValue().set(KEY_PREFIX + code, value, expiry);
        } catch (RuntimeException ex) {
            errors.increment();
            log.debug("Cache write failed for {}", code, ex);
        }
    }

    static Duration clampToExpiry(Duration ttl, Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return ttl;
        }
        Duration untilExpiry = Duration.between(now, expiresAt);
        return untilExpiry.compareTo(ttl) < 0 ? untilExpiry : ttl;
    }

    /** Cache answer: either a known target URL, or a positively known absence. */
    public record Lookup(boolean exists, String longUrl) {

        static Lookup found(String longUrl) {
            return new Lookup(true, longUrl);
        }

        static Lookup missing() {
            return new Lookup(false, null);
        }
    }
}
