-- Per-(owner, year) cache for /api/me/yearly-stats/{year}. Shared across replicas because
-- the spec rules out a JVM-local cache. TTL is enforced in the application by comparing
-- now() against computed_at; rows are kept indefinitely and UPSERT-overwritten on misses.
--
-- recompute_count is incremented on every actual recompute (not on cache hits). It exists
-- so observability / load tests can detect cache-stampede behavior — a properly coordinated
-- implementation refreshes the value at most once per TTL window per key under concurrent
-- demand; an uncoordinated implementation refreshes once per concurrent request.
CREATE TABLE yearly_stats_cache (
    owner_id        BIGINT      NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    year            INT         NOT NULL,
    payload         JSONB       NOT NULL,
    computed_at     TIMESTAMPTZ NOT NULL,
    recompute_count BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_id, year)
);
