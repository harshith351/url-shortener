package com.url.shortener.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.config.AppProperties;
import com.url.shortener.service.ShortCodeGenerator;
import com.url.shortener.web.dto.ErrorResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

/**
 * Fixed-window per-IP rate limiting backed by Redis.
 *
 * <p>Design notes:
 * <ul>
 *   <li><b>Fails open.</b> If Redis is unavailable, requests are allowed. Rate limiting
 *       protects capacity; it is not an authorisation control, so a limiter outage must
 *       not become a service outage. Where the limiter is the security boundary (it is
 *       not here), the correct choice would be the opposite.</li>
 *   <li><b>Fixed window, not sliding.</b> A fixed window admits up to 2x the limit across
 *       a boundary. That is acceptable for abuse damping and costs one INCR per request;
 *       a sliding-log or token bucket in Lua is the upgrade if precision starts to matter.</li>
 *   <li><b>Separate budgets.</b> Creating links is expensive and rare; following them is
 *       cheap and constant. One shared limit would either throttle real traffic or fail
 *       to constrain bulk creation.</li>
 * </ul>
 *
 * <p><b>Deployment requirement:</b> {@code X-Forwarded-For} is only trustworthy behind a
 * proxy that overwrites it. If this service is ever exposed directly, the header must be
 * ignored, or any client can forge an unlimited number of identities.
 */
@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Counter rejected;
    private final Counter limiterErrors;

    public RateLimitFilter(StringRedisTemplate redis,
                           AppProperties properties,
                           ObjectMapper objectMapper,
                           Clock clock,
                           MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.rejected = Counter.builder("shortener.ratelimit.rejected").register(meters);
        this.limiterErrors = Counter.builder("shortener.ratelimit.errors").register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !properties.ratelimit().enabled()
                || path.startsWith("/actuator")
                || path.startsWith("/docs")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        int limit = limitFor(request);
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }

        String bucket = "rl:" + bucketName(request) + ":" + clientIp(request)
                + ":" + (clock.millis() / WINDOW.toMillis());

        long count;
        try {
            Long incremented = redis.opsForValue().increment(bucket);
            count = incremented == null ? 0L : incremented;
            if (count == 1L) {
                // Only the first writer in a window sets the TTL, so the window does not
                // slide forward on every request and never becomes an immortal key.
                redis.expire(bucket, WINDOW);
            }
        } catch (RuntimeException ex) {
            limiterErrors.increment();
            log.debug("Rate limiter unavailable; allowing request", ex);
            chain.doFilter(request, response);
            return;
        }

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - count)));

        if (count > limit) {
            rejected.increment();
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(WINDOW.toSeconds()));
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of("rate_limited", "Too many requests", clock.instant()));
    }

    private int limitFor(HttpServletRequest request) {
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/links".equals(request.getRequestURI())) {
            return properties.ratelimit().createPerMinute();
        }
        if ("GET".equalsIgnoreCase(request.getMethod()) && isRedirectPath(request.getRequestURI())) {
            return properties.ratelimit().redirectPerMinute();
        }
        return 0;
    }

    private String bucketName(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) ? "create" : "redirect";
    }

    private static boolean isRedirectPath(String uri) {
        return uri.length() > 1
                && uri.indexOf('/', 1) < 0
                && ShortCodeGenerator.CODE_PATTERN
                        .matcher(uri.substring(1)).matches();
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
