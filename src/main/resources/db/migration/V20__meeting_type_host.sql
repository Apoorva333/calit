-- Feature: multi-host meeting types. One row per host (creator + co-hosts) of a meeting type.
-- Single-host types have NO rows here. Multi-host = >=1 COHOST row (+ one CREATOR row).
CREATE TABLE meeting_type_host (
    id                    BIGSERIAL   PRIMARY KEY,
    meeting_type_id       BIGINT      NOT NULL REFERENCES meeting_type(id) ON DELETE CASCADE,
    owner_id              BIGINT      NOT NULL REFERENCES app_user(id)     ON DELETE CASCADE,
    status                VARCHAR(16) NOT NULL,   -- PENDING | ACCEPTED
    role                  VARCHAR(16) NOT NULL,   -- CREATOR | COHOST
    consent_token         UUID,                   -- one-click email accept; cleared on response
    buffer_before_minutes INT,                    -- per-host override; NULL = inherit type
    buffer_after_minutes  INT,                    -- per-host override; NULL = inherit type
    created_at            TIMESTAMPTZ NOT NULL,
    responded_at          TIMESTAMPTZ,
    CONSTRAINT uq_meeting_type_host UNIQUE (meeting_type_id, owner_id)
);
CREATE INDEX idx_mth_owner ON meeting_type_host (owner_id);
CREATE INDEX idx_mth_type  ON meeting_type_host (meeting_type_id);
