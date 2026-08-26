package com.url.shortener.service;

import com.url.shortener.domain.ApiKey;
import com.url.shortener.error.UnauthenticatedException;
import com.url.shortener.repo.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues and verifies API keys.
 *
 * <p><b>Why plain SHA-256 and not bcrypt/argon2.</b> Adaptive hashes exist to make brute
 * force expensive against <em>low-entropy human-chosen passwords</em>. These keys are 256
 * bits from a CSPRNG; there is no dictionary to run and no feasible search. A slow KDF
 * would add per-request latency to defend against an attack that is already impossible,
 * and would push this lookup off its index. If keys ever become user-chosen, this reasoning
 * collapses and the algorithm must change.
 *
 * <p>Verification is a lookup by hash against a unique index, so the comparison happens in
 * the database and no timing-sensitive comparison is performed in application code.
 */
@Service
public class ApiKeyService {

    private static final String PREFIX = "sk_";
    private static final int KEY_BYTES = 32;

    private final ApiKeyRepository keys;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    /** The raw key is returned exactly once, at issue time, and is never recoverable. */
    public record IssuedKey(Long id, String name, String rawKey) {
    }

    @Transactional
    public IssuedKey issue(String name) {
        byte[] material = new byte[KEY_BYTES];
        random.nextBytes(material);
        String raw = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        ApiKey saved = keys.save(new ApiKey(hash(raw), name, clock.instant()));
        return new IssuedKey(saved.getId(), saved.getName(), raw);
    }

    /**
     * @return the key's id, or empty when no credential was presented
     * @throws UnauthenticatedException if a credential was presented but is not valid
     */
    @Transactional(readOnly = true)
    public Optional<Long> resolve(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(keys.findByKeyHashAndActiveTrue(hash(rawKey.trim()))
                .orElseThrow(UnauthenticatedException::new)
                .getId());
    }

    static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the JLS; unreachable on any conformant JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
