package com.url.shortener.error;

import org.springframework.http.HttpStatus;

public class InvalidExpiryException extends ApiException {
    public InvalidExpiryException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid_expiry", message);
    }
}
