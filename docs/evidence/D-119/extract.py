"""D-119 probe XML(gzip 가능)에서 D110_RESULT 줄을 뽑아 results.jsonl 과 summary.md 를 만든다.

사용: python3 -B docs/evidence/D-119/extract.py <out_dir> <label>=<xml[.gz]> ...
  예: ... docs/evidence/D-119 base-pool10=base-pool10.xml.gz c-pool10=c-pool10.xml.gz c-pool64=c-pool64.xml.gz
probe 는 D-110 과 같다(OrderPoolContentionLocalProbeTest). 판정 규칙은 docs/plans/D-119.md §검증 방법 V4 (결과 전에 고정).
지표: 따라잡기 비율 = 부하 구간 발행/s ÷ 부하 구간 반복(주문)/s, 반복 3회 중앙값.
"""
import gzip
import html
import json
import re
import statistics
import sys
from pathlib import Path

MARK = re.compile(r'D110_RESULT (\{.*?\})\s*$')


def rows(path):
    opener = gzip.open if path.endswith('.gz') else open
    with opener(path, 'rt', encoding='utf-8') as f:
        for line in f:
            m = MARK.search(html.unescape(line))
            if m:
                yield json.loads(m.group(1))


def summarize(label, path):
    data = list(rows(path))
    env = [r for r in data if r['kind'] == 'environment']
    assert len(env) == 1, f'{path}: environment row {len(env)}'
    integrity = {r['scope'] for r in data if r['kind'] == 'integrity'}
    assert integrity >= {'warmup', 'repetition-1', 'repetition-2', 'repetition-3', 'control'}, \
        f'{path}: integrity rows missing (incomplete run)'
    by = lambda kind: sorted((r for r in data if r['kind'] == kind), key=lambda r: r['repetition'])
    solo, load, drain = by('solo'), by('load'), by('drain')
    assert len(solo) == len(load) == len(drain) == env[0]['repetitions'], f'{path}: phase rows'
    control = by('load-no-poller')
    assert len(control) == 1 and control[0]['publishedDb'] == 0, f'{path}: negative control'
    keep_up = [l['publishedPerSec'] / l['iterationsPerSec'] for l in load]
    ratio = [l['publishedPerSec'] / d['publishedPerSec'] for l, d in zip(load, drain)]
    return data, {
        'label': label,
        'pool': env[0]['pool'],
        'load_hold_ms': statistics.median(r['usageMeanMs'] for r in load),
        'load_acquire_ms': statistics.median(r['acquireMeanMs'] for r in load),
        'load_pending_avg': statistics.median(r['pendingAvg'] for r in load),
        'load_iter_per_s': statistics.median(r['iterationsPerSec'] for r in load),
        'load_pub_per_s': statistics.median(r['publishedPerSec'] for r in load),
        'drain_pub_per_s': statistics.median(r['publishedPerSec'] for r in drain),
        'publish_ratio': statistics.median(ratio),
        'keep_up': statistics.median(keep_up),
        'keep_up_range': (min(keep_up), max(keep_up)),
    }


def verdict(base, c10):
    k, b = c10['keep_up'], base['keep_up']
    if k >= 0.9:
        return f'K1 충족 — 풀 10 따라잡기 비율 {k:.2f} ≥ 0.9'
    if b > 0 and k >= 10 * b:
        return f'K2 부분 — 풀 10 따라잡기 비율 {k:.2f} < 0.9, 기준 코드 {b:.3f} 의 {k / b:.1f}배 (≥ 10배)'
    return f'K3 미충족 — 풀 10 따라잡기 비율 {k:.2f}, 기준 코드 {b:.3f} 의 {k / b if b else float("inf"):.1f}배 (< 10배)'


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    all_rows, summary = [], {}
    for arg in sys.argv[2:]:
        label, path = arg.split('=', 1)
        data, s = summarize(label, path)
        summary[label] = s
        all_rows += [{'source': label, **r} for r in data]

    with open(out / 'results.jsonl', 'w', encoding='utf-8') as f:
        for r in all_rows:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')

    lines = ['| run | 풀 | 부하 점유 ms | 부하 획득 대기 ms | 부하 pending 평균 | 부하 반복/s | 부하 발행/s | 배수 발행/s |'
             ' 발행비 | 따라잡기 비율 (범위) |',
             '|---|---|---|---|---|---|---|---|---|---|']
    for s in summary.values():
        lo, hi = s['keep_up_range']
        lines.append(f"| {s['label']} | {s['pool']} | {s['load_hold_ms']:.2f} | {s['load_acquire_ms']:.1f} |"
                     f" {s['load_pending_avg']:.1f} | {s['load_iter_per_s']:.1f} | {s['load_pub_per_s']:.1f} |"
                     f" {s['drain_pub_per_s']:.1f} | {s['publish_ratio']:.2f} | {s['keep_up']:.3f} ({lo:.3f}~{hi:.3f}) |")
    if 'base-pool10' in summary and 'c-pool10' in summary:
        lines += ['', f"판정: {verdict(summary['base-pool10'], summary['c-pool10'])}"]
    (out / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
