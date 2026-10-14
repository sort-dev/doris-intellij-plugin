#!/usr/bin/env bash
# Starts one Apache Doris all-in-one container for the live introspection lane, runs the lane, and
# always removes the container and its network again. Usage: scripts/doris-live.sh [gradle args...]
set -euo pipefail

image=${DORIS_LIVE_IMAGE:-apache/doris:all-in-one-4.1.3}
name=doris-live-introspection
network=doris-live-introspection
port=${DORIS_LIVE_PORT:-19030}
here=$(cd "$(dirname "$0")/.." && pwd)

others=$(docker ps --format '{{.Names}} {{.Image}}' | grep -Ei 'doris|clickhouse|trino' | grep -v "^$name " || true)
if [[ -n $others ]]; then
    echo "Another engine container is running; the live lane waits for it to stop:" >&2
    echo "$others" >&2
    exit 2
fi

driver=$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j" -name 'mysql-connector-j-9*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)
[[ -n $driver ]] || { echo "No mysql-connector-j 9.x jar in the Gradle cache" >&2; exit 1; }

cleanup() {
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker network create "$network" >/dev/null
docker run -d --name "$name" --network "$network" --memory 6g --memory-swap 6g \
    -p "127.0.0.1:$port:9030" "$image" >/dev/null

for _ in $(seq 1 120); do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo missing)
    [[ $status == healthy ]] && break
    [[ $status == missing || $(docker inspect -f '{{.State.Running}}' "$name") != true ]] && { docker logs --tail 50 "$name" >&2; exit 1; }
    sleep 2
done
[[ $(docker inspect -f '{{.State.Health.Status}}' "$name") == healthy ]] || { echo "Doris did not become healthy" >&2; exit 1; }

docker exec "$name" mkdir -p /opt/apache-doris/jdbc
docker cp "$driver" "$name:/opt/apache-doris/jdbc/mysql-connector-j.jar"
docker exec -i "$name" mysql -uroot -h127.0.0.1 -P9030 <<'SQL'
CREATE DATABASE IF NOT EXISTS sales;
CREATE DATABASE IF NOT EXISTS ops;
CREATE TABLE IF NOT EXISTS sales.orders (id BIGINT, amount DECIMAL(10, 2), placed DATETIME) DISTRIBUTED BY HASH(id) BUCKETS 1;
CREATE TABLE IF NOT EXISTS sales.customers (id BIGINT, name VARCHAR(64)) DISTRIBUTED BY HASH(id) BUCKETS 1;
CREATE TABLE IF NOT EXISTS ops.orders (id BIGINT, status VARCHAR(16)) DISTRIBUTED BY HASH(id) BUCKETS 1;
CREATE DATABASE IF NOT EXISTS archive_src;
CREATE TABLE IF NOT EXISTS archive_src.facts (ts BIGINT, uid BIGINT, kind VARCHAR(16)) DISTRIBUTED BY HASH(uid) BUCKETS 1;
INSERT INTO archive_src.facts VALUES (1700000000, 7, 'a');
CREATE CATALOG IF NOT EXISTS ext_catalog PROPERTIES (
    "type" = "jdbc", "user" = "root", "password" = "",
    "jdbc_url" = "jdbc:mysql://127.0.0.1:9030/archive_src",
    "driver_url" = "file:///opt/apache-doris/jdbc/mysql-connector-j.jar",
    "driver_class" = "com.mysql.cj.jdbc.Driver",
    "include_database_list" = "archive_src"
);
SHOW CATALOGS;
SELECT COUNT(*) FROM ext_catalog.archive_src.facts;
SQL

cd "$here"
./gradlew test --tests 'dev.sort.doris.catalog.DorisIntrospectionLiveTest' \
    -Pdoris.live.url="jdbc:mysql://127.0.0.1:$port/" -Pdoris.live.driverJar="$driver" "$@"
