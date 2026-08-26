package com.url.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * System of record for a short link. Deletion is soft ({@code active = false}) so that
 * a retired code is never handed out again and analytics history stays interpretable.
 */
@Entity
@Table(name = "link")
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 16, updatable = false)
    private String code;

    @Column(name = "long_url", nullable = false)
    private String longUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "idempotency_key", length = 128, updatable = false)
    private String idempotencyKey;

    /** Null means the link predates ownership. See AuthMode. */
    @Column(name = "owner_key_id", updatable = false)
    private Long ownerKeyId;

    protected Link() {
        // for JPA
    }

    /** Retained so existing callers compile unchanged; creates an unowned link. */
    public Link(String code, String longUrl, Instant createdAt, Instant expiresAt, String idempotencyKey) {
        this(code, longUrl, createdAt, expiresAt, idempotencyKey, null);
    }

    public Link(String code, String longUrl, Instant createdAt, Instant expiresAt,
                String idempotencyKey, Long ownerKeyId) {
        this.code = code;
        this.longUrl = longUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey;
        this.ownerKeyId = ownerKeyId;
        this.active = true;
    }

    /** True when the link should no longer resolve, for any reason. */
    public boolean isExpiredAt(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isResolvableAt(Instant now) {
        return active && !isExpiredAt(now);
    }

    public Long getId() { return id; }

    public String getCode() { return code; }

    public String getLongUrl() { return longUrl; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getExpiresAt() { return expiresAt; }

    public boolean isActive() { return active; }

    public void deactivate() { this.active = false; }

    public String getIdempotencyKey() { return idempotencyKey; }

    public Long getOwnerKeyId() { return ownerKeyId; }
}
