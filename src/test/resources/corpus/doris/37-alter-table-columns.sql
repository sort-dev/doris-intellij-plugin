ALTER TABLE acme_events ADD COLUMN category VARCHAR(32) DEFAULT "unknown" AFTER tags;
ALTER TABLE acme_events MODIFY COLUMN tags VARCHAR(256);
ALTER TABLE acme_events DROP COLUMN category;
