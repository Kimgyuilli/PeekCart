"""run 마다 부하 구간 [t1-60s, t1] 의 MySQL 디스크·order 커넥션 풀·CPU 지표를 Prometheus 에서 뽑는다(세션 분석용)."""
import json
import sys
import urllib.parse
import urllib.request

PROM = 'http://localhost:9090/api/v1/query'
DISK = 'instance="10.178.0.24:9100",device="sdb"'
ORDER = 'application="order-service"'
RUNS = [(name, int(t0), int(t1)) for name, t0, t1 in (a.split(':') for a in sys.argv[1:])]

EXPRS = {
    'used_MiB@t0': ('t0', 'kubelet_volume_stats_used_bytes{persistentvolumeclaim="mysql-pvc"} / 2^20'),
    'w_iops': ('t1', f'increase(node_disk_writes_completed_total{{{DISK}}}[60s]) / 60'),
    'w_lat_ms': ('t1', f'increase(node_disk_write_time_seconds_total{{{DISK}}}[60s]) / increase(node_disk_writes_completed_total{{{DISK}}}[60s]) * 1000'),
    'w_MiBps': ('t1', f'increase(node_disk_written_bytes_total{{{DISK}}}[60s]) / 60 / 2^20'),
    'util': ('t1', f'increase(node_disk_io_time_seconds_total{{{DISK}}}[60s]) / 60'),
    'hk_pend_avg': ('t1', f'avg_over_time(sum(hikaricp_connections_pending{{{ORDER}}})[60s:5s])'),
    'hk_pend_max': ('t1', f'max_over_time(sum(hikaricp_connections_pending{{{ORDER}}})[60s:5s])'),
    'hk_acq_ms': ('t1', f'sum(increase(hikaricp_connections_acquire_seconds_sum{{{ORDER}}}[60s])) / sum(increase(hikaricp_connections_acquire_seconds_count{{{ORDER}}}[60s])) * 1000'),
    'hk_use_ms': ('t1', f'sum(increase(hikaricp_connections_usage_seconds_sum{{{ORDER}}}[60s])) / sum(increase(hikaricp_connections_usage_seconds_count{{{ORDER}}}[60s])) * 1000'),
    'mysql_cpu': ('t1', 'sum(rate(container_cpu_usage_seconds_total{namespace="peekcart",pod=~"mysql-.*",container="mysql"}[60s]))'),
    'order_cpu': ('t1', 'sum(rate(container_cpu_usage_seconds_total{namespace="peekcart",pod=~"order-service-.*",container="order-service"}[60s]))'),
    'order_thr': ('t1', 'sum(increase(container_cpu_cfs_throttled_periods_total{namespace="peekcart",pod=~"order-service-.*",container="order-service"}[60s])) / sum(increase(container_cpu_cfs_periods_total{namespace="peekcart",pod=~"order-service-.*",container="order-service"}[60s]))'),
}


def instant(expr, at):
    url = PROM + '?' + urllib.parse.urlencode({'query': expr, 'time': at})
    result = json.load(urllib.request.urlopen(url, timeout=30))['data']['result']
    return float(result[0]['value'][1]) if result else None


print('run\t' + '\t'.join(EXPRS))
for name, t0, t1 in RUNS:
    cells = []
    for key, (when, expr) in EXPRS.items():
        v = instant(expr, t0 if when == 't0' else t1)
        cells.append('-' if v is None else f'{v:.2f}')
    print(name + '\t' + '\t'.join(cells))
