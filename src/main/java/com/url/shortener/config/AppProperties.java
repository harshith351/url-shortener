package com.url.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

/**
 * Strongly typed configuration. Every tunable in this service is here rather than
 * scattered as constants, so operational behaviour can be changed without a rebuild.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String baseUrl,
        Code code,
        Cache cache,
        RateLimit ratelimit,
        Analytics analytics,
        Security security,
        Auth auth) {

    public record Code(int length, int maxGenerationAttempts, Set<String> reserved) {}

    public record Cache(Duration ttl, Duration negativeTtl) {}

    public record RateLimit(boolean enabled, int createPerMinute, int redirectPerMinute) {}

    public record Analytics(int queueCapacity, Duration flushInterval, int flushBatchSize) {}

    public record Security(boolean blockPrivateNetworks, int maxUrlLength) {}

    public record Auth(AuthMode mode, String adminToken) {}
}
