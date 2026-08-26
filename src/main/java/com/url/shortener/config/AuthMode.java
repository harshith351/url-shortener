package com.url.shortener.config;

/**
 * Rollout states for API-key authentication, in the order they are deployed.
 *
 * <p>This enum exists because ownership was added to a service that already had clients.
 * A single deploy that started rejecting unauthenticated requests would have broken every
 * one of them, so the change ships as expand / migrate / contract:
 *
 * <ol>
 *   <li>{@link #DISABLED} -- the new column and table exist; nothing enforces anything.
 *       Deployable at any time, reversible by rollback.</li>
 *   <li>{@link #OPTIONAL} -- keys are honoured. New links get an owner; owned links are
 *       protected; pre-existing unowned links stay accessible. Clients migrate at their
 *       own pace. This is where the service sits during the backfill.</li>
 *   <li>{@link #REQUIRED} -- a key is mandatory. Safe only once no unowned links remain
 *       that anyone still needs to administer.</li>
 * </ol>
 *
 * <p>Redirects are never authenticated in any mode. Ownership governs administration of
 * a link, not the public act of following it.
 */
public enum AuthMode {
    DISABLED,
    OPTIONAL,
    REQUIRED
}
