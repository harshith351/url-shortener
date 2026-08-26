package com.url.shortener.web.dto;

import java.time.Instant;
import java.util.List;

/** Uniform error envelope. Callers can branch on {@code error} without parsing prose. */
public record ErrorResponse(String error, String message, List<String> details, Instant timestamp) {

    public static ErrorResponse of(String error, String message, Instant timestamp) {
        return new ErrorResponse(error, message, List.of(), timestamp);
    }
}
