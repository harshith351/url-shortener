package com.url.shortener.error;

import org.springframework.http.HttpStatus;

/** Raised when repeated code-generation attempts all collided -- a capacity signal. */
public class CodeGenerationException extends ApiException {
    public CodeGenerationException(int attempts) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "code_generation_failed",
                "Could not allocate a unique short code after " + attempts + " attempts");
    }
}
