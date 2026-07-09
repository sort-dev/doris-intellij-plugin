BACKUP SNAPSHOT acme_db.snapshot_20260708
TO acme_repo
ON (acme_events, acme_orders)
PROPERTIES ("type" = "full");
RESTORE SNAPSHOT acme_db.snapshot_20260708
FROM acme_repo
ON (acme_events)
PROPERTIES (
    "backup_timestamp" = "2026-07-08-00-00-00",
    "replication_num" = "1"
);
