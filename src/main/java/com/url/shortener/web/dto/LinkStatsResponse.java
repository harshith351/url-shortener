package com.url.shortener.web.dto;

import com.url.shortener.service.LinkService;

import java.time.LocalDate;
import java.util.List;

/**
 * Click figures are eventually consistent: a redirect that happened moments ago may not
 * be included yet. {@code freshnessSeconds} states that contract in the payload itself
 * rather than leaving callers to discover it.
 */
public record LinkStatsResponse(String code,
                                String shortUrl,
                                String longUrl,
                                long totalClicks,
                                List<DailyPoint> daily,
                                List<ReferrerPoint> topReferrers,
                                long freshnessSeconds) {

    public record DailyPoint(LocalDate day, long clicks) {
    }

    public record ReferrerPoint(String host, long clicks) {
    }

    public static LinkStatsResponse from(LinkService.Stats stats, String baseUrl, long freshnessSeconds) {
        List<DailyPoint> daily = stats.daily().stream()
                .map(d -> new DailyPoint(d.getClickDay(), d.getClicks()))
                .toList();
        List<ReferrerPoint> referrers = stats.topReferrers().stream()
                .map(r -> new ReferrerPoint(r.getHost().isEmpty() ? "(direct)" : r.getHost(), r.getClicks()))
                .toList();
        return new LinkStatsResponse(
                stats.link().getCode(),
                baseUrl + "/" + stats.link().getCode(),
                stats.link().getLongUrl(),
                stats.totalClicks(),
                daily,
                referrers,
                freshnessSeconds);
    }
}
