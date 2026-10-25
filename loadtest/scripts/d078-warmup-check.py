#!/usr/bin/env python3
"""D-108 워밍업 수렴 판정 (docs/plans/D-108.md §3.1).

직전 워밍업 run 과 이번 run 의 k6 요약(--summary-export JSON)에서 주문 수락 건수를 읽어, 변화율이 임계 이내면
수렴이다. 2026-10-25 세션에서 결과를 본 뒤 워밍업 규칙을 바꾼 일(D-108 ④)이 다시 없도록 세션 전에 고정한 기준이다.
재시작 여부는 이 스크립트가 보지 않는다. run 사이에 order·product 재시작이 있었으면 수렴으로 보지 않는다.

사용:
  python3 loadtest/scripts/d078-warmup-check.py <직전 run.json> <이번 run.json> [--threshold 5]

종료 코드: 0 수렴, 1 미수렴, 2 입력 오류.
"""
import argparse
import json
import sys


def counts(path):
    """요약 JSON 에서 (수락 건수, 드롭 건수). 드롭이 0건이면 k6 가 키를 넣지 않으므로 0 으로 본다."""
    with open(path, encoding='utf-8') as f:
        metrics = json.load(f)['metrics']
    accepted = metrics.get('peekcart_order_accepted')
    if accepted is None:
        raise KeyError(f'{path}: peekcart_order_accepted 가 없다')
    dropped = metrics.get('dropped_iterations') or {}
    return int(accepted['count']), int(dropped.get('count', 0))


def main():
    parser = argparse.ArgumentParser(description='D-108 워밍업 수렴 판정')
    parser.add_argument('previous')
    parser.add_argument('current')
    parser.add_argument('--threshold', type=float, default=5.0, help='수렴으로 보는 수락 변화율 상한(%%), 기본 5')
    args = parser.parse_args()
    try:
        prev_accepted, prev_dropped = counts(args.previous)
        cur_accepted, cur_dropped = counts(args.current)
    except (OSError, ValueError, KeyError) as e:
        print(f'워밍업 판정: 입력 오류. {e}')
        return 2
    if prev_accepted == 0:
        print(f'워밍업 판정: 미수렴. 직전 수락이 0건이라 변화율을 낼 수 없다(이번 {cur_accepted}건)')
        return 1
    change = (cur_accepted - prev_accepted) / prev_accepted * 100
    converged = abs(change) <= args.threshold
    print(f'워밍업 판정: {"수렴" if converged else "미수렴"}. 수락 {prev_accepted}건에서 {cur_accepted}건, '
          f'변화 {change:+.1f}% (임계 {args.threshold:g}%). 드롭 {prev_dropped}건에서 {cur_dropped}건')
    return 0 if converged else 1


if __name__ == '__main__':
    sys.exit(main())
