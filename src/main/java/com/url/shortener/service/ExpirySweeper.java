package com.url.shortener.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Marks expired links inactive on a schedule.
 *
 * <p>This is housekeeping, not enforcement: {@link LinkService#resolve} already refuses
 * expired links at read time, so correctness does not depend on the sweeper running.
 * That ordering is deliberate -- a background job that is the only thing standing
 * between an expired link and a live redirect is a job whose failure is a security bug.
 */
@Component
public class ExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    private final LinkWriter writer;
    private final Clock clock;

    public ExpirySweeper(LinkWriter writer, Clock clock) {
        this.writer = writer;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void sweep() {
        int deactivated = writer.deactivateExpired(clock.instant());
        if (deactivated > 0) {
            log.info("Deactivated {} expired links", deactivated);
        }
    }
}
