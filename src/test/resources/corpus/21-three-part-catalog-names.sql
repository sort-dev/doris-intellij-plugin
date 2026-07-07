SELECT * FROM hive_archive.acme_archive.events WHERE dt = '2026-01-01';
SELECT e.a, i.b FROM hive_archive.acme_archive.events e JOIN internal.acme_import.events i ON e.k = i.k;
INSERT INTO hive_archive.acme_archive.events SELECT * FROM s;
