package com.url.shortener.security;

import com.url.shortener.config.AuthMode;
import com.url.shortener.security.AuthorizationPolicy.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full truth table, enumerated. Access control is where a missed case is a breach
 * rather than a bug, so every (mode x owner x caller) combination is asserted explicitly
 * instead of relying on a few representative examples.
 */
class AuthorizationPolicyTest {

    private final AuthorizationPolicy policy = new AuthorizationPolicy();

    private static Long id(String value) {
        return "null".equals(value) ? null : Long.valueOf(value);
    }

    @ParameterizedTest(name = "[{index}] mode={0} owner={1} caller={2} -> {3}")
    @DisplayName("administration decisions across every mode, owner and caller combination")
    @CsvSource({
            // Ownership is not enforced at all before the rollout begins.
            "DISABLED, null, null, ALLOW",
            "DISABLED, 1,    null, ALLOW",
            "DISABLED, 1,    2,    ALLOW",

            // OPTIONAL: legacy unowned links stay reachable -- this is what makes the
            // rollout non-breaking for clients that have not adopted keys yet.
            "OPTIONAL, null, null, ALLOW",
            "OPTIONAL, null, 1,    ALLOW",
            // Owned links are protected the moment a key exists.
            "OPTIONAL, 1,    null, UNAUTHENTICATED",
            "OPTIONAL, 1,    1,    ALLOW",
            "OPTIONAL, 1,    2,    FORBIDDEN",

            // REQUIRED: the same legacy link is now refused, which is exactly why the
            // backfill must complete before this flip.
            "REQUIRED, null, null, FORBIDDEN",
            "REQUIRED, null, 1,    FORBIDDEN",
            "REQUIRED, 1,    null, UNAUTHENTICATED",
            "REQUIRED, 1,    1,    ALLOW",
            "REQUIRED, 1,    2,    FORBIDDEN"})
    void administrationTruthTable(String mode, String owner, String caller, String expected) {
        assertThat(policy.canAdminister(AuthMode.valueOf(mode), id(owner), id(caller)))
                .isEqualTo(Decision.valueOf(expected));
    }

    @ParameterizedTest(name = "[{index}] mode={0} caller={1} -> {2}")
    @CsvSource({
            "DISABLED, null, ALLOW",
            "DISABLED, 1,    ALLOW",
            "OPTIONAL, null, ALLOW",
            "OPTIONAL, 1,    ALLOW",
            "REQUIRED, null, UNAUTHENTICATED",
            "REQUIRED, 1,    ALLOW"})
    void creationTruthTable(String mode, String caller, String expected) {
        assertThat(policy.canCreate(AuthMode.valueOf(mode), id(caller)))
                .isEqualTo(Decision.valueOf(expected));
    }

    @Test
    @DisplayName("owner identity is compared by value, not reference")
    void comparesOwnerIdsByValue() {
        // Long caching stops at 127; an id above that would fail a reference comparison.
        Long owner = 1_000L;
        Long caller = 1_000L;
        assertThat(policy.canAdminister(AuthMode.REQUIRED, owner, caller)).isEqualTo(Decision.ALLOW);
    }
}
