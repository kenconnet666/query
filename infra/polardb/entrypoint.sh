#!/bin/bash
# The image starts bare mysqld with no data directory. On first start this initializes one, waits until the
# single node is the Paxos leader (writes are refused before that), then creates the query database, the query
# user and a remote root, all with QUERY_DB_PASSWORD. Later starts just run mysqld. lower_case_table_names is
# fixed when the data directory is created, so it is passed on every start and must not change afterwards.
set -euo pipefail

: "${QUERY_DB_PASSWORD:?Set QUERY_DB_PASSWORD}"
LCTN="${QUERY_POLARDB_LOWER_CASE_TABLE_NAMES:-1}"
DEFAULTS=(--defaults-file=/polardb/my.cnf "--lower-case-table-names=${LCTN}")
READY=/data/mysql/.query-ready

if [ ! -d /data/mysql/mysql ]; then
    mkdir -p /data/mysql
    mysqld "${DEFAULTS[@]}" --initialize-insecure
fi

mysqld "${DEFAULTS[@]}" &
pid=$!

stop() {
    MYSQL_PWD="${QUERY_DB_PASSWORD}" mysqladmin --protocol=TCP -h 127.0.0.1 -u root shutdown 2>/dev/null \
        || mysqladmin --protocol=TCP -h 127.0.0.1 -u root shutdown 2>/dev/null || kill "$pid"
    wait "$pid"
}
trap stop TERM INT

if [ ! -f "$READY" ]; then
    for _ in $(seq 1 120); do
        role=$(mysql --protocol=TCP -h 127.0.0.1 -u root -N -e \
            "SELECT ROLE FROM information_schema.ALISQL_CLUSTER_LOCAL" 2>/dev/null || true)
        [ "$role" = "Leader" ] && break
        sleep 1
    done
    [ "$role" = "Leader" ] || { echo "the node did not become leader in two minutes" >&2; exit 1; }
    mysql --protocol=TCP -h 127.0.0.1 -u root <<SQL
CREATE DATABASE IF NOT EXISTS query;
CREATE USER IF NOT EXISTS 'query'@'%' IDENTIFIED BY '${QUERY_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON query.* TO 'query'@'%';
CREATE USER IF NOT EXISTS 'root'@'%' IDENTIFIED BY '${QUERY_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;
ALTER USER 'root'@'localhost' IDENTIFIED BY '${QUERY_DB_PASSWORD}';
SQL
    touch "$READY"
    echo "query database and users are ready"
fi

wait "$pid"
