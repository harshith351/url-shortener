package com.url.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A credential. Only the SHA-256 of the key is ever persisted, so a database disclosure
 * does not hand over working credentials.
 */
@Entity
@Table(name = "api_key")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_hash", nullable = false, length = 64, updatable = false)
    private String keyHash;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ApiKey() {
        // for JPA
    }

    public ApiKey(String keyHash, String name, Instant createdAt) {
        this.keyHash = keyHash;
        this.name = name;
        this.createdAt = createdAt;
        this.active = true;
    }

    public Long getId() { return id; }

    public String getKeyHash() { return keyHash; }

    public String getName() { return name; }

    public Instant getCreatedAt() { return createdAt; }

    public boolean isActive() { return active; }

    public void revoke() { this.active = false; }
}
