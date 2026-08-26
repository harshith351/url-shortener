package com.url.shortener;

import com.url.shortener.config.AppProperties;
import com.url.shortener.config.AuthMode;

import java.time.Duration;
import java.util.Set;

/** Shared, explicit configuration for unit tests -- no Spring context required. */
public final class TestProperties {

    private TestProperties() {
    }

    public static AppProperties defaults() {
        return new AppProperties(
                "https://sho.rt",
                new AppProperties.Code(7, 3, Set.of("links", "health", "actuator", "docs")),
                new AppProperties.Cache(Duration.ofMinutes(10), Duration.ofSeconds(30)),
                new AppProperties.RateLimit(true, 60, 600),
                new AppProperties.Analytics(1000, Duration.ofSeconds(2), 500),
                new AppProperties.Security(true, 2048),
                new AppProperties.Auth(AuthMode.DISABLED, "admin-token"));
    }

    public static AppProperties withPrivateNetworksAllowed() {
        AppProperties base = defaults();
        return new AppProperties(base.baseUrl(), base.code(), base.cache(), base.ratelimit(),
                base.analytics(), new AppProperties.Security(false, 2048), base.auth());
    }

    /** Defaults with authentication in the given rollout state. */
    public static AppProperties withAuth(AuthMode mode) {
        AppProperties base = defaults();
        return new AppProperties(base.baseUrl(), base.code(), base.cache(), base.ratelimit(),
                base.analytics(), base.security(), new AppProperties.Auth(mode, "admin-token"));
    }
}
