#!/usr/bin/env bash
# docs/plans/done/INDEX.md 를 생성한다.
#
#   아카이브된 계획서 45개를 평면으로 두고 찾기는 인덱스로 푼다.
#
# 디렉터리를 Phase 별이나 주제별로 더 나누지 않는 이유는 둘이다. 하나는 파일명이 이미
# `task-adrNNNN-`, `task-implN-`, `task-dNNN-` 으로 분류 축을 담고 있어 디렉터리가 같은
# 정보를 중복한다는 것이다. 다른 하나는 디렉터리를 나눌 때마다 인바운드 참조가 깨진다는
# 것이다. 실제로 과거 done/ 이동에서 17곳이 조용히 끊겨 있었다.
#
# 실제 문제는 "45개 중 어느 것이 무엇인가" 이고 그것은 디렉터리로 풀리지 않는다. 열어봐야
# 알기 때문이다. 그래서 제목과 PR 과 날짜를 한 장에 모은다.
#
# INDEX.md 는 생성물이다. 손으로 고치지 않는다. 낡으면 다시 돌린다.
#
# 사용:
#   plans-index.sh            INDEX.md 생성 (기본)
#   plans-index.sh --check    내용이 최신인지만 확인. 다르면 exit 1 (CI/lint 용)
#   plans-index.sh --stdout   파일을 쓰지 않고 표준출력으로
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2
DONE="docs/plans/done"
OUT="${DONE}/INDEX.md"

render() {
  python3 - "$DONE" <<'PY'
import glob, os, re, sys

done = sys.argv[1]
rows = []
for f in sorted(glob.glob(os.path.join(done, '*.md'))):
    base = os.path.basename(f)
    if base.endswith('.audit.md') or base == 'INDEX.md':
        continue
    stem = f[:-3]
    audit = stem + '.audit.md'
    plan_txt = open(f, encoding='utf-8').read()
    txt = plan_txt + (open(audit, encoding='utf-8').read() if os.path.exists(audit) else '')

    tid = base[:-3]

    # 제목: 첫 '# ' 헤딩에서 장식(계획 —, task-id —)을 걷어낸다.
    m = re.search(r'^#\s+(.+)$', plan_txt, re.M)
    title = m.group(1).strip() if m else ''
    title = re.sub(r'^계획\s*[—-]\s*', '', title)
    title = re.sub(r'^%s\s*[—-]\s*' % re.escape(tid), '', title)
    title = title.replace('|', '\\|').strip()

    prs = sorted({int(x) for x in re.findall(r'pull/(\d+)', txt)})
    dates = sorted(set(re.findall(r'20\d{2}-\d{2}-\d{2}', txt)))
    rows.append({
        'tid': tid,
        'title': title,
        'pr': prs[-1] if prs else None,
        'date': dates[-1] if dates else '',
        'audit': os.path.exists(audit),
    })

# 분류 축은 파일명 접두사가 이미 담고 있다. 그대로 쓴다.
def bucket(tid):
    if re.match(r'^task-adr\d+', tid):  return ('ADR 설계', 1)
    if re.match(r'^task-impl', tid):    return ('구현', 2)
    if re.match(r'^task-d\d+', tid):    return ('부채 · 측정', 3)
    return ('기타', 4)

groups = {}
for r in rows:
    name, order = bucket(r['tid'])
    groups.setdefault((order, name), []).append(r)

out = []
out.append('# 완료 계획서 인덱스')
out.append('')
out.append('`scripts/plans-index.sh` 가 생성한다. 손으로 고치지 않는다.')
out.append('')
out.append('계획서 %d개. 진행 중인 계획서는 `docs/plans/` 루트에 있다.' % len(rows))
out.append('아카이브 기준은 PR 머지 여부다 (`scripts/plans-archive.sh`).')
out.append('')
for (order, name) in sorted(groups):
    rs = sorted(groups[(order, name)], key=lambda r: (r['date'] or '', r['tid']))
    out.append('## %s (%d)' % (name, len(rs)))
    out.append('')
    out.append('| 계획서 | 내용 | PR | 날짜 | audit |')
    out.append('|---|---|---|---|---|')
    for r in rs:
        pr = ('[#%d](https://github.com/Kimgyuilli/PeakCart/pull/%d)' % (r['pr'], r['pr'])) if r['pr'] else ''
        out.append('| [%s](./%s.md) | %s | %s | %s | %s |'
                   % (r['tid'], r['tid'], r['title'], pr, r['date'], '있음' if r['audit'] else ''))
    out.append('')
print('\n'.join(out).rstrip() + '\n')
PY
}

case "${1:-}" in
  --stdout) render ;;
  --check)
    tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
    render > "$tmp"
    if [ -f "$OUT" ] && cmp -s "$tmp" "$OUT"; then
      echo "INDEX.md 최신"
    else
      echo "INDEX.md 가 낡았다. scripts/plans-index.sh 로 다시 생성한다." >&2
      exit 1
    fi
    ;;
  ""|--write)
    render > "$OUT"
    echo "생성: $OUT ($(grep -c '^| \[' "$OUT") 행)"
    ;;
  -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' ;;
  *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
esac
