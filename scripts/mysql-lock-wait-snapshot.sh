#!/usr/bin/env bash
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-coinflow-mysql-1}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-root}"
MYSQL_DATABASE="${MYSQL_DATABASE:-coinflow}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-2}"
DURATION_SECONDS="${DURATION_SECONDS:-300}"
INNODB_STATUS_EVERY_SECONDS="${INNODB_STATUS_EVERY_SECONDS:-30}"
OUTPUT_DIR="${OUTPUT_DIR:-/private/tmp/coinflow-mysql-lock-wait}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"

RUN_DIR="${OUTPUT_DIR}/${RUN_ID}"
DETAIL_LOG="${RUN_DIR}/mysql-lock-wait-detail.log"
SUMMARY_TSV="${RUN_DIR}/mysql-lock-wait-summary.tsv"
INNODB_LOG="${RUN_DIR}/mysql-innodb-status.log"

mkdir -p "${RUN_DIR}"

mysql_exec() {
  docker exec \
    -e MYSQL_PWD="${MYSQL_PASSWORD}" \
    "${MYSQL_CONTAINER}" \
    mysql \
    -u"${MYSQL_USER}" \
    --database="${MYSQL_DATABASE}" \
    "$@"
}

mysql_batch() {
  mysql_exec --batch --raw --skip-column-names -e "$1"
}

mysql_table() {
  mysql_exec --table -e "$1"
}

cat > "${SUMMARY_TSV}" <<'HEADER'
epoch	local_time	mysql_time	data_lock_waits	data_locks	active_trx	lock_wait_trx	running_trx
HEADER

cat > "${DETAIL_LOG}" <<HEADER
CoinFlow MySQL lock wait snapshot
run_id=${RUN_ID}
container=${MYSQL_CONTAINER}
database=${MYSQL_DATABASE}
interval_seconds=${INTERVAL_SECONDS}
duration_seconds=${DURATION_SECONDS}
innodb_status_every_seconds=${INNODB_STATUS_EVERY_SECONDS}
started_at=$(date '+%Y-%m-%d %H:%M:%S')

HEADER

cat > "${INNODB_LOG}" <<HEADER
CoinFlow MySQL InnoDB status snapshot
run_id=${RUN_ID}
started_at=$(date '+%Y-%m-%d %H:%M:%S')

HEADER

START_EPOCH="$(date +%s)"
END_EPOCH="$((START_EPOCH + DURATION_SECONDS))"
NEXT_INNODB_EPOCH="${START_EPOCH}"

echo "Writing MySQL lock wait snapshots to ${RUN_DIR}"

while [ "$(date +%s)" -le "${END_EPOCH}" ]; do
  NOW_EPOCH="$(date +%s)"
  SNAPSHOT_TIME="$(date '+%Y-%m-%d %H:%M:%S')"

  SUMMARY_SQL="
SELECT
  NOW(3),
  (SELECT COUNT(*) FROM performance_schema.data_lock_waits),
  (SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_SCHEMA = '${MYSQL_DATABASE}'),
  (SELECT COUNT(*) FROM information_schema.innodb_trx),
  (SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'),
  (SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'RUNNING');
"
  SUMMARY_ROW="$(mysql_batch "${SUMMARY_SQL}" | head -n 1)"
  printf '%s\t%s\t%s\n' "${NOW_EPOCH}" "${SNAPSHOT_TIME}" "${SUMMARY_ROW}" >> "${SUMMARY_TSV}"

  {
    printf '\n===== %s epoch=%s =====\n' "${SNAPSHOT_TIME}" "${NOW_EPOCH}"
    printf '\n-- lock wait edges\n'
    mysql_table "
SELECT
  w.REQUESTING_ENGINE_TRANSACTION_ID AS waiting_trx,
  w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_trx,
  rl.OBJECT_NAME AS waiting_table,
  rl.INDEX_NAME AS waiting_index,
  rl.LOCK_TYPE AS waiting_type,
  rl.LOCK_MODE AS waiting_mode,
  rl.LOCK_STATUS AS waiting_status,
  LEFT(rl.LOCK_DATA, 80) AS waiting_data,
  bl.OBJECT_NAME AS blocking_table,
  bl.INDEX_NAME AS blocking_index,
  bl.LOCK_TYPE AS blocking_type,
  bl.LOCK_MODE AS blocking_mode,
  LEFT(bl.LOCK_DATA, 80) AS blocking_data
FROM performance_schema.data_lock_waits w
LEFT JOIN performance_schema.data_locks rl
  ON rl.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
LEFT JOIN performance_schema.data_locks bl
  ON bl.ENGINE_LOCK_ID = w.BLOCKING_ENGINE_LOCK_ID
ORDER BY waiting_trx
LIMIT 20;
"
    printf '\n-- lock count by table/status/mode\n'
    mysql_table "
SELECT
  OBJECT_NAME,
  INDEX_NAME,
  LOCK_STATUS,
  LOCK_MODE,
  COUNT(*) AS lock_count
FROM performance_schema.data_locks
WHERE OBJECT_SCHEMA = '${MYSQL_DATABASE}'
GROUP BY OBJECT_NAME, INDEX_NAME, LOCK_STATUS, LOCK_MODE
ORDER BY lock_count DESC, OBJECT_NAME
LIMIT 30;
"
    printf '\n-- active innodb transactions\n'
    mysql_table "
SELECT
  trx_id,
  trx_state,
  TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS age_seconds,
  IFNULL(TIMESTAMPDIFF(SECOND, trx_wait_started, NOW()), 0) AS wait_seconds,
  trx_tables_locked,
  trx_lock_structs,
  trx_rows_locked,
  trx_rows_modified,
  trx_operation_state,
  LEFT(trx_query, 160) AS trx_query
FROM information_schema.innodb_trx
ORDER BY age_seconds DESC, trx_id
LIMIT 20;
"
    printf '\n-- innodb row lock global status\n'
    mysql_table "
SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';
"
    printf '\n-- coinflow processlist\n'
    mysql_table "
SELECT
  ID,
  USER,
  DB,
  COMMAND,
  TIME,
  STATE,
  LEFT(INFO, 160) AS INFO
FROM information_schema.PROCESSLIST
WHERE DB = '${MYSQL_DATABASE}'
ORDER BY TIME DESC, ID
LIMIT 20;
"
    printf '\n-- top statement digests\n'
    mysql_table "
SELECT
  SCHEMA_NAME,
  COUNT_STAR,
  ROUND(SUM_TIMER_WAIT / 1000000000000, 3) AS total_seconds,
  ROUND(AVG_TIMER_WAIT / 1000000000, 3) AS avg_ms,
  LEFT(DIGEST_TEXT, 160) AS digest_text
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = '${MYSQL_DATABASE}'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 15;
"
  } >> "${DETAIL_LOG}" 2>&1

  if [ "${NOW_EPOCH}" -ge "${NEXT_INNODB_EPOCH}" ]; then
    {
      printf '\n===== %s epoch=%s =====\n' "${SNAPSHOT_TIME}" "${NOW_EPOCH}"
      mysql_exec -e "SHOW ENGINE INNODB STATUS\G"
    } >> "${INNODB_LOG}" 2>&1
    NEXT_INNODB_EPOCH="$((NOW_EPOCH + INNODB_STATUS_EVERY_SECONDS))"
  fi

  sleep "${INTERVAL_SECONDS}"
done

echo "MySQL lock wait snapshot completed"
echo "summary=${SUMMARY_TSV}"
echo "detail=${DETAIL_LOG}"
echo "innodb=${INNODB_LOG}"
