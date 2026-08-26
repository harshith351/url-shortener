package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import com.url.shortener.error.InvalidAliasException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Owns what a short code may look like.
 *
 * <p>Codes are drawn from a CSPRNG rather than derived from a sequence. Sequential or
 * hash-derived codes leak information: a competitor can enumerate every link ever
 * created, and monotonic codes disclose creation volume. See ADR-0001.
 */
@Component
public class ShortCodeGenerator {

    /** Base62. No ambiguity stripping: it would shrink the space for a cosmetic gain. */
    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** Codes and custom aliases share one shape so routing stays unambiguous. */
    public static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{3,16}$");

    private final SecureRandom random = new SecureRandom();
    private final int length;
    private final Set<String> reserved;

    public ShortCodeGenerator(AppProperties properties) {
        this.length = properties.code().length();
        this.reserved = properties.code().reserved();
    }

    public String generate() {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /**
     * Validates a user-supplied alias. Rejects anything that could shadow a real route,
     * which is why reserved words are matched case-insensitively.
     */
    public String validateAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            throw new InvalidAliasException("Alias must not be blank");
        }
        String trimmed = alias.trim();
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidAliasException(
                    "Alias must be 3-16 characters of letters, digits, hyphen or underscore");
        }
        if (isReserved(trimmed)) {
            throw new InvalidAliasException("The alias '" + trimmed + "' is reserved");
        }
        return trimmed;
    }

    public boolean isReserved(String candidate) {
        String lower = candidate.toLowerCase(Locale.ROOT);
        return reserved.stream().anyMatch(r -> r.toLowerCase(Locale.ROOT).equals(lower));
    }
}
