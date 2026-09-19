#!/usr/bin/env bash
# 프로젝트 상태를 한 장으로 요약한다. `/sync`, `/next`, `/done` 의 읽기 비용을 대신한다.
#
#   TASKS.md 94KB + PHASE4.md 294KB + adr/README.md 6KB = 395KB 를 약 1KB 로 줄인다.
#
# 종전에는 커맨드가 위 세 문서를 통째로 읽으라고 지시했다. 실제로는 보고 템플릿 7줄
# (Phase · 진행 중 Task · 완료 카운트 · 최근 결정 · ADR 이상 · 파일 · 다음 작업)만 쓰이고,
# 그 7줄은 전부 파싱으로 나온다. 읽기가 잘려 grep 을 여러 번 다시 도는 일도 있었다.
#
# **파서가 고장나면 조용히 비는 대신 크게 실패한다.** 형식이 바뀌었는데 0건을 정상으로
# 보고하면 "진행 중 작업 없음" 이라는 거짓 요약이 나가기 때문이다. 각 구획은 최소 1건을
# 기대하고, 못 읽으면 stderr 에 적고 exit 3 이다 (ci-test-matrix-lint 의 파서 고장 검사와 같은 규약).
#
# 사용:
#   harness-context.sh              다이제스트 출력 (기본)
#   harness-context.sh --phase N    PHASE{N}.md 를 지정 (기본: TASKS.md 에서 추론)
#   harness-context.sh --check      파서가 각 구획을 읽어내는지만 확인
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

MODE="${1:---digest}"
PHASE_ARG=""
[ "${1:-}" = "--phase" ] && { PHASE_ARG="${2:-}"; MODE="--digest"; }

python3 - "$MODE" "$PHASE_ARG" <<'PY'
import os, re, subprocess, sys

mode, phase_arg = sys.argv[1], sys.argv[2]
problems, out = [], []

def read(p):
    try:
        return open(p, encoding='utf-8').read()
    except Exception:
        problems.append('읽기 실패: %s' % p)
        return ''

# ---------- TASKS.md ----------
tasks = read('docs/TASKS.md')
m = re.search(r'^## 현재 단계:\s*(.+)$', tasks, re.M)
if not m:
    problems.append('TASKS.md 에서 "## 현재 단계:" 를 찾지 못했다 (형식 변경?)')
phase_line = m.group(1).strip() if m else '?'
out.append('## 단계')
out.append(phase_line)

def cells(line):
    return [c.strip() for c in line.strip().strip('|').split('|')]

# 상태는 **마지막 칸**으로만 판정한다. 행 전체에서 이모지를 찾으면 본문에 이모지가
# 섞인 완료 행이 진행중으로 잡힌다(구현 ③·④ 에서 실제로 오탐했다).
def status_of(line):
    c = cells(line)
    return c[-1] if c else ''

live = []
for l in tasks.split('\n'):
    if not l.startswith('|'):
        continue
    st = status_of(l)
    if '🔄' in st or '🔲' in st:
        live.append(l)
out.append('')
out.append('## 진행 중 / 대기 (%d)' % len(live))
if not live:
    out.append('(없음)')
for l in live:
    c = cells(l)
    ident = c[0][:8] if c else '?'
    desc = c[1] if len(c) > 1 else ''
    out.append('- [%s] %s | %s'
               % (ident, desc[:90], '진행중' if '🔄' in status_of(l) else '대기'))

done_rows = len([l for l in tasks.split('\n')
                 if l.startswith('|') and '✅' in status_of(l)])
out.append('')
out.append('완료 표기 행 %d · 진행중/대기 행 %d' % (done_rows, len(live)))

# ---------- ADR 인덱스 ----------
adr = read('docs/adr/README.md')
adr_rows = [l for l in adr.split('\n') if re.match(r'^\|\s*\[\d{4}\]', l)]
if not adr_rows:
    problems.append('adr/README.md 에서 ADR 행을 하나도 읽지 못했다 (형식 변경?)')
odd = []
for l in adr_rows:
    c = cells(l)
    num = re.search(r'\[(\d{4})\]', c[0])
    status = c[2] if len(c) > 2 else ''
    if status and status != 'Accepted':
        odd.append((num.group(1) if num else '?', status))
out.append('')
out.append('## ADR (%d개 중 비-Accepted %d개)' % (len(adr_rows), len(odd)))
for n, s in odd:
    out.append('- %s %s' % (n, s))
if not odd:
    out.append('(전부 Accepted)')

# ---------- progress ----------
pnum = phase_arg or (re.search(r'Phase\s*(\d+)', phase_line).group(1)
                     if re.search(r'Phase\s*(\d+)', phase_line) else '')
ppath = 'docs/progress/PHASE%s.md' % pnum if pnum else ''
if ppath and os.path.exists(ppath):
    prog = read(ppath)
    heads = re.findall(r'^## (.+)$', prog, re.M)
    if not heads:
        problems.append('%s 에서 "## " 엔트리를 읽지 못했다' % ppath)
    out.append('')
    out.append('## progress 최근 (%s, 전체 %d엔트리)' % (os.path.basename(ppath), len(heads)))
    for h in heads[-4:]:
        out.append('- %s' % h[:100])
else:
    problems.append('progress 문서를 찾지 못했다: %s' % (ppath or '(Phase 번호 추론 실패)'))

# ---------- 계획서 ----------
import glob
root_plans = [os.path.basename(p) for p in glob.glob('docs/plans/*.md')
              if not p.endswith('.audit.md') and 'PLAN-BLINDSPOTS' not in p]
out.append('')
out.append('## 계획서')
out.append('진행 중 %d개%s · 아카이브 %d개'
           % (len(root_plans),
              (' (' + ', '.join(p[:-3] for p in root_plans[:5]) + ')') if root_plans else '',
              len([p for p in glob.glob('docs/plans/done/*.md')
                   if not p.endswith('.audit.md') and 'INDEX' not in p])))

# ---------- git ----------
def sh(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout.strip()
out.append('')
out.append('## git')
out.append('브랜치 %s · 미커밋 %s개'
           % (sh('git', 'branch', '--show-current'),
              len([x for x in sh('git', 'status', '--porcelain').split('\n') if x])))
log = sh('git', 'log', '--oneline', '-5')
if not log:
    problems.append('git log 를 읽지 못했다')
for l in log.split('\n'):
    out.append('- %s' % l[:95])

# ---------- 출력 ----------
if mode == '--check':
    if problems:
        print('파서 고장 %d건' % len(problems))
        for p in problems:
            print('  %s' % p)
        sys.exit(3)
    print('ok — 모든 구획을 읽었다')
    sys.exit(0)

if problems:
    for p in problems:
        sys.stderr.write('경고: %s\n' % p)
print('\n'.join(out))
if problems:
    sys.exit(3)
PY
