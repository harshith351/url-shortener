package com.url.shortener.error;

import org.springframework.http.HttpStatus;

/**
 * The message is intentionally identical whether the link belongs to someone else or the
 * caller simply lacks rights over it. A distinguishing message would let an attacker use
 * the endpoint to confirm which codes exist and who owns them.
 */
public class ForbiddenException extends ApiException {
    public ForbiddenException() {
        super(HttpStatus.FORBIDDEN, "forbidden", "This API key may not administer that link");
    }
}
