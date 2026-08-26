package com.url.shortener.error;

import org.springframework.http.HttpStatus;

public class AliasTakenException extends ApiException {
    public AliasTakenException(String alias) {
        super(HttpStatus.CONFLICT, "alias_taken", "The alias '" + alias + "' is already in use");
    }
}
