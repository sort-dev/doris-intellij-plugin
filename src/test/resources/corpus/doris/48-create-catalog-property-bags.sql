CREATE CATALOG hive_acme PROPERTIES (
    'type' = 'hms',
    'hive.metastore.uris' = 'thrift://10.0.0.10:9083',
    'hadoop.username' = 'hive'
);
CREATE CATALOG iceberg_acme PROPERTIES (
    'type' = 'iceberg',
    'iceberg.catalog.type' = 'rest',
    'uri' = 'http://10.0.0.20:8181'
);
CREATE CATALOG jdbc_acme PROPERTIES (
    'type' = 'jdbc',
    'user' = 'acme_ro',
    'password' = 'Passw0rd!',
    'jdbc_url' = 'jdbc:mysql://10.0.0.30:3306/acme_db',
    'driver_url' = 'mysql-connector-j-8.3.0.jar',
    'driver_class' = 'com.mysql.cj.jdbc.Driver'
);
