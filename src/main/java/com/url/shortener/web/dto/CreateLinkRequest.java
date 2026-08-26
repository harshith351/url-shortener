package com.url.shortener.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateLinkRequest(

        @Schema(description = "Absolute http(s) URL to shorten", example = "https://example.com/a/very/long/path")
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,

        @Schema(description = "Optional custom alias; 3-16 chars of [A-Za-z0-9_-]", example = "q3-report")
        String alias,

        @Schema(description = "Optional expiry instant (ISO-8601, UTC)", example = "2026-12-31T23:59:59Z")
        Instant expiresAt) {
}
