package com.url.shortener.error;

import org.springframework.http.HttpStatus;

/**
 * Raised when an idempotency key matches a link belonging to a different owner.
 *
 * <p>Idempotency keys are unique service-wide, not per-owner (a consequence of the
 * original single-tenant schema). Once ownership exists, returning the matched link
 * would let one tenant read another's links by guessing a key such as "order-1".
 * Refusing is the safe answer; scoping the uniqueness constraint to the owner is the
 * proper fix and is deferred to a later migration.
 */
public class IdempotencyKeyConflictException extends ApiException {
    public IdempotencyKeyConflictException() {
        super(HttpStatus.CONFLICT, "idempotency_key_conflict",
                "That Idempotency-Key is already in use by another owner");
    }
}
