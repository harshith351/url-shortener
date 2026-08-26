package com.url.shortener.web.dto;

import com.url.shortener.domain.Link;

import java.time.Instant;

public record LinkResponse(String code,
                           String shortUrl,
                           String longUrl,
                           Instant createdAt,
                           Instant expiresAt,
                           boolean active) {

    public static LinkResponse from(Link link, String baseUrl) {
        return new LinkResponse(
                link.getCode(),
                baseUrl + "/" + link.getCode(),
                link.getLongUrl(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.isActive());
    }
}
