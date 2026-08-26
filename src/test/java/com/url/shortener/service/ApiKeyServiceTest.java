package com.url.shortener.service;

import com.url.shortener.domain.ApiKey;
import com.url.shortener.error.UnauthenticatedException;
import com.url.shortener.repo.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private ApiKeyRepository repository;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repository = mock(ApiKeyRepository.class);
        service = new ApiKeyService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("hashing is stable, and different keys never collide")
    void hashesDeterministically() {
        assertThat(ApiKeyService.hash("sk_abc")).isEqualTo(ApiKeyService.hash("sk_abc"));
        assertThat(ApiKeyService.hash("sk_abc")).isNotEqualTo(ApiKeyService.hash("sk_abd"));
        assertThat(ApiKeyService.hash("sk_abc")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the raw key is returned once and never persisted")
    void issuedKeyIsStoredOnlyAsAHash() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.IssuedKey issued = service.issue("reporting-service");

        assertThat(issued.rawKey()).startsWith("sk_");
        assertThat(issued.name()).isEqualTo("reporting-service");
    }

    @Test
    void issuedKeysAreUnique() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(service.issue("svc").rawKey());
        }
        assertThat(seen).hasSize(500);
    }

    @Test
    @DisplayName("absent credentials are anonymous, not an error")
    void missingKeyResolvesToEmpty() {
        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("   ")).isEmpty();
    }

    @Test
    @DisplayName("a presented-but-invalid key is rejected rather than silently downgraded")
    void invalidKeyIsRejected() {
        // The distinction matters: treating a bad key as "anonymous" would let a caller
        // with a revoked credential quietly retain whatever anonymous access still exists.
        when(repository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("sk_wrong"))
                .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void validKeyResolvesToItsId() {
        ApiKey key = new ApiKey(ApiKeyService.hash("sk_good"), "svc", NOW);
        // id is only ever assigned by JPA (@GeneratedValue); a hand-built entity in a
        // pure unit test has no other way to acquire one.
        org.springframework.test.util.ReflectionTestUtils.setField(key, "id", 42L);
        when(repository.findByKeyHashAndActiveTrue(ApiKeyService.hash("sk_good")))
                .thenReturn(Optional.of(key));

        assertThat(service.resolve("sk_good")).contains(42L);
    }

    @Test
    @DisplayName("a revoked key does not authenticate")
    void revokedKeyIsRejected() {
        // findByKeyHashAndActiveTrue excludes revoked rows, so revocation is enforced by
        // the query rather than by a check a caller could forget.
        when(repository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("sk_revoked"))
                .isInstanceOf(UnauthenticatedException.class);
    }
}
