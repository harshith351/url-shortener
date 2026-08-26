package com.url.shortener.error;

import org.springframework.http.HttpStatus;

public class InvalidAliasException extends ApiException {
    public InvalidAliasException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid_alias", message);
    }
}
