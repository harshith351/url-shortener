package com.url.shortener.repo;

import com.url.shortener.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * Lookup is by hash against a unique index, so the database performs the comparison.
     * No key material is loaded and compared in application code.
     */
    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);
}
