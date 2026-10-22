#!/usr/bin/env python3
"""D-078 A·B 지연 집계. `loadtest/sql/d078-*-latency.sql` 이 낸 TSV(`mysql -B`)를 읽는다.

  saga    <saga.tsv>      → 사가 왕복 지연 p50/p95/p99, 매칭·제외 건수
  outbox  <outbox.tsv>... → 발행 대기 p50/p95/p99, 파일·이벤트 타입별

백분위는 nearest-rank 다(보간하지 않는다). 표본이 0 이면 값을 지어내지 않고 `-` 로 적는다.
NULL(미확정·미발행)은 0 으로 세지 않고 제외 건수로 따로 센다.
"""
import argparse
import csv
import math
import sys
from collections import defaultdict
from pathlib import Path

PERCENTILES = (50, 95, 99)


def nearest_rank(sorted_values, pct):
    if not sorted_values:
        return None
    rank = max(1, math.ceil(pct / 100 * len(sorted_values)))
    return sorted_values[rank - 1]


def read_rows(path):
    with open(path, newline='', encoding='utf-8') as handle:
        return list(csv.DictReader(handle, delimiter='\t'))


def parse_us(value):
    """`mysql -B` 는 NULL 을 문자열 'NULL' 로 낸다. 음수는 시계 이상이라 거부한다."""
    if value is None or value == 'NULL' or value == '':
        return None
    number = int(value)
    if number < 0:
        raise ValueError(f'음수 지연 {number}us — 두 시각의 기록 주체나 시계를 확인하라')
    return number


def summarize(values):
    values = sorted(values)
    return {f'p{p}': nearest_rank(values, p) for p in PERCENTILES} | {'n': len(values),
                                                                      'max': values[-1] if values else None}


def ms(us):
    return '-' if us is None else f'{us / 1000:.1f}'


def row(label, summary, excluded):
    return (f'{label}\t{summary["n"]}\t{excluded}\t' + '\t'.join(ms(summary[f'p{p}']) for p in PERCENTILES)
            + f'\t{ms(summary["max"])}')


HEADER = 'group\tn\texcluded\tp50_ms\tp95_ms\tp99_ms\tmax_ms'


def cmd_saga(args):
    rows = read_rows(args.file)
    values, excluded = [], defaultdict(int)
    for r in rows:
        us = parse_us(r['reserve_roundtrip_us'])
        if us is None:
            excluded[r['status']] += 1
        else:
            values.append(us)
    print(HEADER)
    print(row('saga-roundtrip', summarize(values), sum(excluded.values())))
    for status, count in sorted(excluded.items()):
        print(f'# excluded status={status} count={count}')
    return 0


def cmd_outbox(args):
    print(HEADER)
    for name in args.files:
        groups, excluded = defaultdict(list), defaultdict(int)
        for r in read_rows(name):
            us = parse_us(r['publish_wait_us'])
            if us is None:
                excluded[r['event_type']] += 1
            else:
                groups[r['event_type']].append(us)
        stem = Path(name).stem
        for event_type in sorted(set(groups) | set(excluded)):
            print(row(f'{stem}:{event_type}', summarize(groups[event_type]), excluded[event_type]))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = parser.add_subparsers(dest='command', required=True)
    saga = sub.add_parser('saga')
    saga.add_argument('file')
    outbox = sub.add_parser('outbox')
    outbox.add_argument('files', nargs='+')
    args = parser.parse_args(argv)
    return {'saga': cmd_saga, 'outbox': cmd_outbox}[args.command](args)


if __name__ == '__main__':
    sys.exit(main())
