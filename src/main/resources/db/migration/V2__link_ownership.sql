-- Ownership, added to a live schema.
--
-- Both statements are additive and take no exclusive lock long enough to matter:
-- CREATE TABLE is trivially safe, and adding a NULLABLE column with no DEFAULT is a
-- catalogue-only change in Postgres 11+ -- no table rewrite, no blocking of the
-- redirect path. A NOT NULL column with a backfill would have been a very different
-- operation on a table this hot. See docs/scenarios/02-brownfield.md.

CREATE TABLE api_key (
    id         BIGSERIAL   PRIMARY KEY,
    key_hash   VARCHAR(64) NOT NULL,
    name       VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE
);

-- Lookup is BY hash, so the index is the comparison. Raw keys are never stored.
CREATE UNIQUE INDEX ux_api_key_hash ON api_key (key_hash);

-- NULL means "created before ownership existed". Those rows are grandfathered while
-- auth runs in 'optional' mode and must be backfilled before it is set to 'required'.
ALTER TABLE link ADD COLUMN owner_key_id BIGINT NULL REFERENCES api_key (id);

CREATE INDEX ix_link_owner_key ON link (owner_key_id) WHERE owner_key_id IS NOT NULL;
