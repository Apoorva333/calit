-- Multi-host: a booking spans N rows (one per host), linked by group_id. NULL = single-host.
ALTER TABLE booking ADD COLUMN group_id UUID;
CREATE INDEX idx_booking_group ON booking (group_id) WHERE group_id IS NOT NULL;
