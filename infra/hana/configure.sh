#!/usr/bin/env bash
set -euo pipefail

# The database-server edition has no XS applications; the HTTP web dispatcher only takes memory from the tenant.
# The image enables it on the HOST layer, the only layer this parameter accepts; Compose pins the host name.
/usr/sap/HXE/HDB90/exe/hdbsql -n 127.0.0.1:39017 -u SYSTEM -p "${QUERY_HANA_PASSWORD:?Set QUERY_HANA_PASSWORD}" -E 1 \
  "ALTER SYSTEM ALTER CONFIGURATION ('daemon.ini', 'HOST', 'hxehost') SET ('webdispatcher', 'instances') = '0' WITH RECONFIGURE"
