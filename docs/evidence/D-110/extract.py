"""D-110 probe XML(gzip 가능)에서 D110_RESULT 줄을 뽑아 results.jsonl 과 summary.md 를 만든다.

사용: python3 -B docs/evidence/D-110/extract.py <out_dir> <pool10.xml[.gz]> <pool20.xml[.gz]> ...
판정 규칙은 docs/plans/D-110.md §3 (결과 전에 고정) 을 그대로 계산한다.
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


def median(values):
    return statistics.median(values) if values else float('nan')


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    all_rows, summary = [], []
    for path in sys.argv[2:]:
        data = list(rows(path))
        env = [r for r in data if r['kind'] == 'environment']
        assert len(env) == 1, f'{path}: environment row {len(env)}'
        pool = env[0]['pool']
        integrity = [r for r in data if r['kind'] == 'integrity']
        assert {r['scope'] for r in integrity} >= {'warmup', 'repetition-1', 'repetition-2', 'repetition-3', 'control'}, \
            f'{path}: integrity rows missing (incomplete run)'
        by = lambda kind: sorted((r for r in data if r['kind'] == kind), key=lambda r: r['repetition'])
        solo, load, drain = by('solo'), by('load'), by('drain')
        assert len(solo) == len(load) == len(drain) == env[0]['repetitions'], f'{path}: phase rows'
        control = by('load-no-poller')
        assert len(control) == 1 and control[0]['publishedDb'] == 0, f'{path}: negative control'
        ratios = [l['publishedPerSec'] / d['publishedPerSec'] for l, d in zip(load, drain)]
        hold_ratio = [l['usageMeanMs'] / s['usageMeanMs'] for l, s in zip(load, solo)]
        summary.append({
            'pool': pool,
            'solo_hold_ms': median([r['usageMeanMs'] for r in solo]),
            'load_hold_ms': median([r['usageMeanMs'] for r in load]),
            'hold_ratio': median(hold_ratio),
            'load_acquire_ms': median([r['acquireMeanMs'] for r in load]),
            'load_pending_avg': median([r['pendingAvg'] for r in load]),
            'load_iter_per_s': median([r['iterationsPerSec'] for r in load]),
            'load_pub_per_s': median([r['publishedPerSec'] for r in load]),
            'drain_pub_per_s': median([r['publishedPerSec'] for r in drain]),
            'publish_ratio': median(ratios),
            'publish_ratio_range': (min(ratios), max(ratios)),
            'collisions': sum(r.get('orderNumberCollisions', 0) for r in data),
        })
        for r in data:
            all_rows.append({'source': Path(path).name, **r})

    with open(out / 'results.jsonl', 'w', encoding='utf-8') as f:
        for r in all_rows:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')

    lines = ['| 풀 | 단독 점유 ms | 부하 점유 ms | 점유비 | 부하 획득 대기 ms | 부하 pending 평균 | 부하 반복/s |'
             ' 부하 발행/s | 배수 발행/s | 발행비 중앙값 (범위) | 주문번호 충돌 |',
             '|---|---|---|---|---|---|---|---|---|---|---|']
    for s in summary:
        lo, hi = s['publish_ratio_range']
        lines.append(f"| {s['pool']} | {s['solo_hold_ms']:.2f} | {s['load_hold_ms']:.2f} | {s['hold_ratio']:.2f} |"
                     f" {s['load_acquire_ms']:.1f} | {s['load_pending_avg']:.1f} | {s['load_iter_per_s']:.1f} |"
                     f" {s['load_pub_per_s']:.1f} | {s['drain_pub_per_s']:.1f} | {s['publish_ratio']:.2f} ({lo:.2f}~{hi:.2f}) |"
                     f" {s['collisions']} |")
    by_pool = {s['pool']: s for s in summary}
    lines.append('')
    if 10 in by_pool and 64 in by_pool:
        p10, p64 = by_pool[10], by_pool[64]
        if p10['publish_ratio'] > 0.5:
            j1 = '보류 (풀 10 발행비 > 0.5: 로컬이 굶주림을 재현하지 못함)'
        elif p64['publish_ratio'] >= 0.8 and p64['load_pending_avg'] < 1:
            j1 = '확정 (풀 10 발행비 ≤ 0.5, 풀 64 발행비 ≥ 0.8·pending < 1)'
        else:
            j1 = '미충족 (풀 10 은 굶지만 풀 64 에서 회복 기준 미달)'
        j2 = '점유 = SQL·커밋 고정 비용 (점유비 ≤ 1.2)' if p10['hold_ratio'] <= 1.2 else '부하에서 점유가 늘어남 (점유비 > 1.2). 늘어난 자리(DB·앱)는 이 실험으로 가르지 못함'
        lines += [f'- J1 풀 경계: {j1}', f'- J2 커밋 비용(풀 10): {j2}']
    (out / 'summary.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('\n'.join(lines))


if __name__ == '__main__':
    main()
