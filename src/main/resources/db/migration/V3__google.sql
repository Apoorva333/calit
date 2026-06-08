-- Owner OAuth tokens. Singleton row (id = 1).
CREATE TABLE google_credential (
    id                  BIGINT       PRIMARY KEY,
    refresh_token       TEXT         NOT NULL,
    access_token        TEXT,
    access_token_expiry TIMESTAMPTZ
);

-- Calendars the owner chose to read (busy) from and/or write to.
CREATE TABLE google_calendar (
    id                 BIGSERIAL    PRIMARY KEY,
    google_calendar_id VARCHAR(255) NOT NULL UNIQUE,
    summary            VARCHAR(255) NOT NULL,
    read_for_busy      BOOLEAN      NOT NULL DEFAULT FALSE,
    write_target       BOOLEAN      NOT NULL DEFAULT FALSE
);

-- At most one write-target calendar: partial unique index over the rows where write_target is true.
CREATE UNIQUE INDEX idx_google_calendar_single_write_target
    ON google_calendar (write_target)
    WHERE write_target = TRUE;
