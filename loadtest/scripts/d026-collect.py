#!/usr/bin/env python3
"""D-026 / D-002a 읽기 경로 — 런 결과 집계.

계획서 §3-2·§3-3. 단일 값이 아니라 **셀당 중앙값과 산포**를 내고,
게이트(재현·대조군·되돌림)를 숫자로 판정한다.

사용:  python3 loadtest/scripts/d026-collect.py [.cache/d026-runs]
"""
import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".cache/d026-runs")

# 기준선 — docs/progress/evidence/d002a-gke-20260916-0030.md 조건 B
BASELINE = {("detail", "off"): 378.1, ("detail", "on"): 463.6,
            ("list", "off"): 380.0, ("list", "on"): 767.1}

cells = defaultdict(list)
for meta in sorted(ROOT.glob("*.meta")):
    m = dict(l.rstrip("\n").split("\t", 1) for l in open(meta) if "\t" in l)
    js = meta.with_suffix(".json")
    if not js.exists():
        print(f"!! k6 요약 없음: {js.name}")
        continue
    d = json.load(open(js))["metrics"]
    # 런 도중 Pod 가 재시작했으면 counter 차분이 무의미하다 → 버린다.
    if m.get("pod_uid") != m.get("pod_uid_after"):
        print(f"!! Pod 재시작 — 폐기: {meta.name}")
        continue
    # 불완전한 런은 조용히 섞이면 안 된다. 사후 스냅샷이 없으면 그 런은 측정이 아니다.
    need = ("hit_after", "miss_after", "product_throttled_after",
            "mysql_throttled_after", "mysql_usage_after", "pod_uid_after")
    missing = [k for k in need if k not in m]
    if missing:
        print(f"!! 사후 스냅샷 누락 — 폐기: {meta.name} (없는 키: {','.join(missing)})")
        continue
    hit = int(m["hit_after"]) - int(m["hit_before"])
    miss = int(m["miss_after"]) - int(m["miss_before"])
    if hit < 0 or miss < 0:
        print(f"!! counter 역전(Pod 재시작 추정) — 폐기: {meta.name}")
        continue
    dur_s = float(m["dur"].rstrip("s"))
    # 블록 라벨은 파일명에서 뽑는다 — meta 의 block 키는 나중에 추가됐고,
    # 그 전에 돈 런도 집계에 들어와야 한다. 파일명은 런너가 항상 만든다.
    block = meta.name.split("-", 1)[0].removeprefix("mysql")
    cells[(block, m["ep"], m["cache_env"] == "true")].append({
        "rps": d["http_reqs"]["rate"],
        "p95": d["http_req_duration"]["p(95)"],
        "avg": d["http_req_duration"]["avg"],
        "fail": d["http_req_failed"]["value"],
        "blocked_p95": d.get("http_req_blocked", {}).get("p(95)", 0),
        "hit": hit, "miss": miss,
        "miss_per_s": miss / dur_s if dur_s else 0,
        "hitrate": hit / (hit + miss) if (hit + miss) else 0,
        "prod_throttle_s": (int(m["product_throttled_after"]) - int(m["product_throttled_before"])) / 1e6,
        "mysql_throttle_s": (int(m["mysql_throttled_after"]) - int(m["mysql_throttled_before"])) / 1e6,
        "mysql_cpu_s": (int(m["mysql_usage_after"]) - int(m["mysql_usage_before"])) / 1e6,
        "mysql_limit": m["mysql_cpu_limit"],
    })


def med(rows, k):
    return statistics.median(r[k] for r in rows)


print(f"\n{'블록':<6}{'EP':<8}{'캐시':<6}{'n':<3}{'rps(중앙)':>11}{'산포':>9}"
      f"{'p95ms':>9}{'miss/s':>8}{'적중률':>8}{'MySQL스로틀s':>13}")
print("-" * 82)
med_rps = {}
for key in sorted(cells):
    mysql, ep, on = key
    rows = cells[key]
    r = sorted(x["rps"] for x in rows)
    spread = (max(r) - min(r)) / statistics.median(r) * 100 if len(r) > 1 else 0
    med_rps[key] = statistics.median(r)
    print(f"{mysql:<6}{ep:<8}{'ON' if on else 'OFF':<6}{len(rows):<3}"
          f"{statistics.median(r):>11.1f}{spread:>8.1f}%{med(rows,'p95'):>9.1f}"
          f"{med(rows,'miss_per_s'):>8.1f}{med(rows,'hitrate')*100:>7.1f}%"
          f"{med(rows,'mysql_throttle_s'):>13.1f}")

print("\n=== 배속 (같은 블록 안 paired) ===")
for mysql in sorted({k[0] for k in med_rps}):
    for ep in ("detail", "list"):
        off, on = med_rps.get((mysql, ep, False)), med_rps.get((mysql, ep, True))
        if off and on:
            base = BASELINE[(ep, "off")], BASELINE[(ep, "on")]
            print(f"  {mysql:<6}{ep:<8}OFF {off:7.1f} → ON {on:7.1f} = ×{on/off:.2f}"
                  f"   (기준선 ×{base[1]/base[0]:.2f})")

print("\n=== 게이트 ===")


def gate(label, got, want, tol):
    if got is None:
        print(f"  [미측정] {label}")
        return
    d = abs(got - want) / want * 100
    print(f"  [{'통과' if d <= tol else '실패'}] {label}: {got:.1f} vs 기준 {want} "
          f"({d:+.1f}%, 허용 ±{tol}%)")


gate("재현 detail OFF", med_rps.get(("A", "detail", False)), 378.1, 10)
gate("재현 list OFF", med_rps.get(("A", "list", False)), 380.0, 10)
gate("대조군 list ON", med_rps.get(("A", "list", True)), 767.1, 15)
for ep in ("detail", "list"):
    a, a2 = med_rps.get(("A", ep, False)), med_rps.get(("A2", ep, False))
    if a and a2:
        gate(f"되돌림 {ep} OFF (A′ vs A)", a2, a, 10)
