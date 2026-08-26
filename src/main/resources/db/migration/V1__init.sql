-- Canonical record for a short link.
CREATE TABLE link (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(16)  NOT NULL,
    long_url        TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    idempotency_key VARCHAR(128) NULL
);

-- The uniqueness guarantee for short codes lives in the database, not in
-- application logic. Code generation races are resolved by this constraint.
CREATE UNIQUE INDEX ux_link_code ON link (code);

-- Partial unique index: many rows may have a NULL key, at most one per value.
CREATE UNIQUE INDEX ux_link_idempotency_key
    ON link (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Supports the expiry sweeper without a full scan.
CREATE INDEX ix_link_expires_at ON link (expires_at) WHERE expires_at IS NOT NULL;

-- Pre-aggregated click counts. We deliberately do NOT store raw click events
-- with IP addresses; see docs/adr/0003-analytics-pipeline.md.
CREATE TABLE link_click_daily (
    code          VARCHAR(16) NOT NULL,
    click_day     DATE        NOT NULL,
    referrer_host VARCHAR(255) NOT NULL,  -- '' means direct / no referrer
    clicks        BIGINT      NOT NULL,
    CONSTRAINT pk_link_click_daily PRIMARY KEY (code, click_day, referrer_host)
);

CREATE INDEX ix_click_daily_code_day ON link_click_daily (code, click_day DESC);
