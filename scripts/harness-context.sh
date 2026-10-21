#!/usr/bin/env bash
# 프로젝트 상태를 한 장으로 요약한다. `/sync`, `/next`, `/done` 의 읽기 비용을 대신한다.
#
#   TASKS.md 94KB + PHASE4.md 294KB + adr/README.md 6KB = 395KB 를 약 1KB 로 줄인다.
#
# 종전에는 커맨드가 위 세 문서를 통째로 읽으라고 지시했다. 실제로는 보고 템플릿 7줄
# (Phase · 진행 중 Task · 완료 카운트 · 최근 결정 · ADR 이상 · 파일 · 다음 작업)만 쓰이고,
# 그 7줄은 전부 파싱으로 나온다. 읽기가 잘려 grep 을 여러 번 다시 도는 일도 있었다.
#
# 읽기/필수 구획 형식과 완료 archive 최소 D행·D-ID 유일성을 검사한다.
# 진행 중 작업·root plan 0개는 정상일 수 있다. 이 건강 검사는 부채 전체 완료 증명이 아니다.
# --check --base는 명시 git 기준의 ID 소실/완료 archive 감소도 검사한다.
# 건강/기준 조회 실패는 exit 3이며 원문·순서·링크 보존은 D-098 별도 검사다.
#
# 사용:
#   harness-context.sh              다이제스트 출력 (기본)
#   harness-context.sh --phase N    PHASE{N}.md 를 지정 (기본: TASKS.md 에서 추론)
#   harness-context.sh --check      현행 읽기/완료 archive 건강 검사(base 비교 없음)
#   harness-context.sh --check --base <commit>  기준 ID 소실/완료 archive 감소도 검사
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

python3 - "$@" <<'PYTHON'
import argparse, os, re, subprocess, sys
from collections import Counter

parser = argparse.ArgumentParser(description='프로젝트 digest와 완료 archive 건강 검사')
parser.add_argument('--check', action='store_true', help='현행 건강 검사(보존/전체 완료 판정 아님)')
parser.add_argument('--phase', default='', help='progress Phase 번호')
parser.add_argument('--base', help='--check에서 비교할 명시 git commit; 생략하면 상대 감소는 검사하지 않음')
args = parser.parse_args()
if args.base is not None and not args.base.strip():
    parser.error('--base는 비어 있을 수 없다')
if args.base is not None and not args.check:
    parser.error('--base는 --check와 함께 사용한다')
if args.phase and not re.fullmatch(r'[0-9]+', args.phase):
    parser.error('--phase는 숫자여야 한다')
mode, phase_arg = ('--check' if args.check else '--digest'), args.phase
problems, out = [], []

def read(p):
    try:
        return open(p, encoding='utf-8').read()
    except Exception:
        problems.append('읽기 실패: %s' % p)
        return ''

def debt_rows(text, label):
    rows = []
    for line in text.splitlines():
        if not re.match(r'^\s*\|\s*D-', line, re.I): continue
        match = re.match(r'^\s*\|\s*(D-[0-9]{3,})\s*\|', line)
        if not match or len(line.strip().strip('|').split('|')) < 5:
            problems.append('%s: 손상된 D행 형식' % label)
            continue
        rows.append((match.group(1), line))
    return rows


def archive_health(text, label):
    rows = debt_rows(text, label)
    if not re.search(r'^## 완료 행\s*$', text, re.M) or not re.search(r'^\s*\|\s*ID\s*\|', text, re.M):
        problems.append('%s: 완료 행 heading/table을 찾지 못했다' % label)
    if not rows:
        problems.append('%s: 완료 D행이 없다' % label)
    for ident, line in rows:
        if '✅' not in status_of(line):
            problems.append('%s: 완료 상태가 없는 archive 행 %s' % (label, ident))
    return rows


def unique_ids(rows, label):
    duplicates = sorted(key for key, n in Counter(key for key, _ in rows).items() if n > 1)
    if duplicates:
        problems.append('%s: 중복 D-ID %s' % (label, ', '.join(duplicates)))


# ---------- TASKS.md ----------
tasks = read('docs/TASKS.md')
m = re.search(r'^## 현재 단계:\s*(.+)$', tasks, re.M)
if not m:
    problems.append('TASKS.md 에서 "## 현재 단계:" 를 찾지 못했다 (형식 변경?)')
phase_line = m.group(1).strip() if m else '?'
out.append('## 단계')
out.append(phase_line)

def cells(line):
    text = line.strip()
    if text.startswith('|'):
        text = text[1:]

    def delimiter(index):
        previous = index - 1
        while previous >= 0 and text[previous] == '\\':
            previous -= 1
        return (index - previous - 1) % 2 == 0

    # Remove one optional border, never the extra empty final cell in "||".
    if text.endswith('|') and delimiter(len(text) - 1):
        text = text[:-1]
    result, start = [], 0
    for index, character in enumerate(text):
        if character == '|' and delimiter(index):
            result.append(text[start:index].strip())
            start = index + 1
    return result + [text[start:].strip()]

# 상태는 **마지막 칸**으로만 판정한다. 행 전체에서 이모지를 찾으면 본문에 이모지가
# 섞인 완료 행이 진행중으로 잡힌다(구현 ③·④ 에서 실제로 오탐했다).
def status_of(line):
    c = cells(line)
    return c[-1] if c else ''

live = []
for l in tasks.split('\n'):
    if not re.match(r'^\s*\|', l):
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

# 완료 행은 TASKS-done.md 로 옮겨지므로 두 파일을 합쳐 센다.
# 건강 검사는 현재 값, 명시 base 검사는 기대 감소를 아는 별도 계약이다.
archive_path = 'docs/progress/TASKS-done.md'
archive = read(archive_path)
active_debts = debt_rows(tasks, 'docs/TASKS.md')
archived_debts = archive_health(archive, archive_path)
unique_ids(active_debts + archived_debts, '현행 task 문서')
if args.base:
    try:
        revision = subprocess.run(['git', 'rev-parse', '--verify', args.base + '^{commit}'],
                                  capture_output=True, text=True)
        if revision.returncode:
            problems.append('base 조회 실패(exit %s): %s' % (revision.returncode, args.base))
        else:
            base = revision.stdout.strip()
            baseline = {}
            for path in ('docs/TASKS.md', archive_path):
                result = subprocess.run(['git', 'show', base + ':' + path], capture_output=True, text=True)
                if result.returncode:
                    problems.append('base 문서 조회 실패(exit %s): %s:%s' % (result.returncode, base, path))
                else:
                    baseline[path] = result.stdout
            if len(baseline) == 2:
                old_active = debt_rows(baseline['docs/TASKS.md'], 'base:docs/TASKS.md')
                old_archive = archive_health(baseline[archive_path], 'base:' + archive_path)
                unique_ids(old_active + old_archive, 'base task 문서')
                current_ids = {key for key, _ in active_debts + archived_debts}
                lost = {key for key, _ in old_active + old_archive} - current_ids
                archive_lost = {key for key, _ in old_archive} - {key for key, _ in archived_debts}
                if lost:
                    problems.append('base 대비 task D-ID 소실: ' + ', '.join(sorted(lost)))
                if archive_lost:
                    problems.append('base 대비 완료 archive 감소: ' + ', '.join(sorted(archive_lost)))
    except (OSError, UnicodeError) as error:
        problems.append('base git 실행/읽기 실패: %s' % error)
done_rows = len([l for l in (tasks + '\n' + archive).split('\n')
                 if re.match(r'^\s*\|', l) and '✅' in status_of(l)])
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
    # 엔트리는 "작업 이력" 뒤의 헤딩만. 앞쪽 개요 헤딩(목표·이월 항목 등)은 엔트리가 아니다
    if '작업 이력' in heads and heads.index('작업 이력') + 1 < len(heads):
        heads = heads[heads.index('작업 이력') + 1:]
    # 파일마다 기록 순서가 다르다(PHASE3·4 는 오래된 것부터, PHASE5 는 최신부터). 날짜로 판별한다
    dates = [m.group(0) for m in (re.search(r'\d{4}-\d{2}-\d{2}', h) for h in heads) if m]
    recent = heads[:4] if len(dates) >= 2 and dates[0] > dates[-1] else heads[-4:][::-1]
    out.append('')
    out.append('## progress 최근 (%s, 전체 %d엔트리)' % (os.path.basename(ppath), len(heads)))
    for h in recent:
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
    try:
        result = subprocess.run(a, capture_output=True, text=True)
    except (OSError, UnicodeError) as error:
        problems.append('git 실행 실패: %s' % error)
        return ''
    if result.returncode:
        problems.append('git 조회 실패(exit %s): %s' % (result.returncode, ' '.join(a)))
        return ''
    return result.stdout.strip()
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
    print('ok — 현행 archive 건강 검사 통과; ' + ('명시 base ID 비교 통과' if args.base else 'base 비교 미실행'))
    sys.exit(0)

if problems:
    for p in problems:
        sys.stderr.write('경고: %s\n' % p)
print('\n'.join(out))
if problems:
    sys.exit(3)
PYTHON
