package com.url.shortener.error;

import org.springframework.http.HttpStatus;

public class LinkNotFoundException extends ApiException {
    public LinkNotFoundException(String code) {
        super(HttpStatus.NOT_FOUND, "link_not_found", "No link exists for code '" + code + "'");
    }
}
