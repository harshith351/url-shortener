package com.url.shortener.error;

import org.springframework.http.HttpStatus;

public class UnauthenticatedException extends ApiException {
    public UnauthenticatedException() {
        super(HttpStatus.UNAUTHORIZED, "unauthenticated",
                "A valid X-API-Key header is required for this operation");
    }
}
