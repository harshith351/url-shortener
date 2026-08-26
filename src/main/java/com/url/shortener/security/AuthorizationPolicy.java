package com.url.shortener.security;

import com.url.shortener.config.AuthMode;
import org.springframework.stereotype.Component;

/**
 * Every authorisation decision in the service, in one place, as a pure function.
 *
 * <p>Deliberately has no dependency on Spring, HTTP, the database or the clock. Access
 * control is the code most costly to get subtly wrong and hardest to inspect when it is
 * scattered across controllers, so it is isolated here where the whole truth table can be
 * read at once and enumerated exhaustively in tests.
 */
@Component
public class AuthorizationPolicy {

    public enum Decision {
        /** Proceed. */
        ALLOW,
        /** No usable credential was presented -- 401. */
        UNAUTHENTICATED,
        /** A valid credential was presented, but it does not own this link -- 403. */
        FORBIDDEN
    }

    /** May the caller create a link? */
    public Decision canCreate(AuthMode mode, Long callerKeyId) {
        if (mode == AuthMode.REQUIRED && callerKeyId == null) {
            return Decision.UNAUTHENTICATED;
        }
        return Decision.ALLOW;
    }

    /**
     * May the caller read statistics for, or retire, this link?
     *
     * <p>The interesting case is {@code ownerKeyId == null} in {@link AuthMode#OPTIONAL}:
     * a link created before ownership existed. Those are allowed through, which is what
     * makes the rollout non-breaking. In {@link AuthMode#REQUIRED} the same link is
     * refused -- which is precisely why the backfill must finish before that flip.
     */
    public Decision canAdminister(AuthMode mode, Long ownerKeyId, Long callerKeyId) {
        if (mode == AuthMode.DISABLED) {
            return Decision.ALLOW;
        }
        if (ownerKeyId == null) {
            return mode == AuthMode.REQUIRED ? Decision.FORBIDDEN : Decision.ALLOW;
        }
        if (callerKeyId == null) {
            return Decision.UNAUTHENTICATED;
        }
        return ownerKeyId.equals(callerKeyId) ? Decision.ALLOW : Decision.FORBIDDEN;
    }
}
