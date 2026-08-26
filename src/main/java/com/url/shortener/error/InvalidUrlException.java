package com.url.shortener.error;

import org.springframework.http.HttpStatus;

public class InvalidUrlException extends ApiException {
    public InvalidUrlException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid_url", message);
    }
}
