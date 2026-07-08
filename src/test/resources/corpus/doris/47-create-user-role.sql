CREATE USER 'acme_analyst'@'%' IDENTIFIED BY 'Passw0rd!';
CREATE USER IF NOT EXISTS 'acme_etl_user'@'10.0.0.%' IDENTIFIED BY 'Passw0rd!' DEFAULT ROLE 'acme_etl';
CREATE ROLE acme_etl;
CREATE ROLE IF NOT EXISTS acme_readonly;
