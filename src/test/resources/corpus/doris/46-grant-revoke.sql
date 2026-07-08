GRANT SELECT_PRIV ON internal.acme_db.acme_events TO 'acme_analyst'@'%';
GRANT LOAD_PRIV, ALTER_PRIV ON acme_db.* TO ROLE 'acme_etl';
GRANT USAGE_PRIV ON WORKLOAD GROUP 'etl' TO 'acme_analyst'@'%';
GRANT 'acme_etl' TO 'acme_jack'@'%';
REVOKE SELECT_PRIV ON acme_db.* FROM 'acme_analyst'@'%';
