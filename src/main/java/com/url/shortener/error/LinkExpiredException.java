package com.url.shortener.error;

import org.springframework.http.HttpStatus;

/**
 * 410 rather than 404: the distinction tells the caller the code was real and is now
 * retired, which is materially different from a typo.
 */
public class LinkExpiredException extends ApiException {
    public LinkExpiredException(String code) {
        super(HttpStatus.GONE, "link_expired", "The link '" + code + "' is no longer available");
    }
}
