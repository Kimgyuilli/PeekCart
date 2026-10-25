#!/usr/bin/env bash
# S3 run 부하 구간의 MySQL 진단 표본 (읽기 전용). OUT 에 남긴다.
#   setup(약 2분) 뒤 부하 60초 동안 10초 간격 7회: threads_running, InnoDB 트랜잭션 상태별 수, 잠금 대기 수,
#   대기 중인 질의 상위. 부하 앞뒤로 전역 상태 카운터(잠금 대기·fsync·커밋)를 떠 차분을 낸다.
set -uo pipefail
OUT="${OUT:?}"
DELAY="${DELAY:-125}"
NS=peekcart
my() { kubectl -n "$NS" exec -i deploy/mysql -- mysql -N -B -uroot -ppeekcart -e "$1" 2>/dev/null; }
status() {
  my "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_os_log_fsyncs','Innodb_data_fsyncs','Com_commit','Handler_commit','Threads_running','Innodb_log_waits','Innodb_buffer_pool_wait_free');"
}
sleep "$DELAY"
{ echo "## status before $(date -u +%FT%TZ) $(date +%s)"; status; } > "$OUT"
for i in 1 2 3 4 5 6 7; do
  {
    echo "## sample $i $(date -u +%FT%TZ)"
    echo "# threads_running"; my "SHOW GLOBAL STATUS LIKE 'Threads_running';"
    echo "# trx by state";   my "SELECT trx_state, COUNT(*) FROM information_schema.INNODB_TRX GROUP BY trx_state;"
    echo "# lock waits";     my "SELECT COUNT(*) FROM performance_schema.data_lock_waits;"
    echo "# waiting queries (top)"
    my "SELECT t.trx_mysql_thread_id, TIMESTAMPDIFF(MICROSECOND, t.trx_wait_started, NOW(6)) AS wait_us, LEFT(REPLACE(t.trx_query, '\n', ' '), 140) FROM information_schema.INNODB_TRX t WHERE t.trx_state = 'LOCK WAIT' ORDER BY wait_us DESC LIMIT 5;"
    echo "# processlist states"
    my "SELECT db, state, COUNT(*) FROM information_schema.PROCESSLIST WHERE command <> 'Sleep' GROUP BY db, state ORDER BY 3 DESC LIMIT 8;"
  } >> "$OUT"
  sleep 10
done
{ echo "## status after $(date -u +%FT%TZ) $(date +%s)"; status; } >> "$OUT"
echo "표본 완료 → $OUT"
