package com.url.shortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CoreConfig {

    /**
     * Time is injected, never read from a static call. Expiry logic is one of the few
     * places in this service where a subtle bug is invisible until a link outlives its
     * TTL in production, so it has to be testable without sleeping.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
