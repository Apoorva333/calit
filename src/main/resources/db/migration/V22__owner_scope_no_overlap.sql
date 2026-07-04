-- The V4 constraint forbade overlaps instance-wide (ignored owner_id) -- a latent multi-tenant bug
-- AND it would make per-host multi-host rows collide. Re-scope it to the owner.
ALTER TABLE booking DROP CONSTRAINT booking_no_overlap_held;
ALTER TABLE booking ADD CONSTRAINT booking_no_overlap_held
    EXCLUDE USING gist (owner_id WITH =, tstzrange(start_utc, end_utc) WITH &&)
    WHERE (status IN ('PENDING', 'CONFIRMED'));
