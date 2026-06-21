ALTER TABLE owner_settings ADD COLUMN locale varchar(8) NOT NULL DEFAULT 'en';
ALTER TABLE booking        ADD COLUMN locale varchar(8) NOT NULL DEFAULT 'en';
