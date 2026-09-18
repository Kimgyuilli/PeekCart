# ---------- Audit log / metrics ----------

# ---------- 리뷰 처분 규율 점검 ----------

# hpx_review_health <task_id>
# audit 파일을 읽어 리뷰 루프가 발산 중인지 알린다. 권고일 뿐 진행을 막지 않는다.
#
# 배경: 실측 18개 계획서 56라운드 442건에서 P0 는 2.0%, 기각률은 0.3% 였다.
# 머지를 막지 않는 지적을 거의 전부 그 자리에서 구현했고, 그래서 라운드마다 범위가 커졌다.
# 이 함수는 그 상태로 되돌아가는 것을 눈에 보이게 한다.
#
# 출력 1행: ok | warnings
#      이후: 경고 항목
# exit: 항상 0 (권고)
hpx_review_health() {
  local task_id="${1-}"
  hpx_task_id_validate "$task_id" 2>/dev/null || { printf 'ok\n'; return 0; }

  local path="docs/plans/${task_id}.audit.md"
  [ -f "$path" ] || path="docs/plans/done/${task_id}.audit.md"
  [ -f "$path" ] || { printf 'ok\n'; return 0; }

  python3 - "$path" <<'PY'
import io, re, sys

text = io.open(sys.argv[1], encoding='utf-8').read()

# 한 파일에 계획 리뷰와 diff 리뷰 루프가 함께 들어가므로 블록 수와 최대 라운드를 구분한다.
blocks = len(re.findall(r'^##.*라운드\s*[1-9]', text, re.M))
maxround = max([int(m) for m in re.findall(r'라운드\s*([1-9])', text)] or [0])

reflect = defer = reject = 0
for m in re.finditer(r'반영\s*\**(\d+)\**건', text):
    reflect += int(m.group(1))
for m in re.finditer(r'이월\s*\**(\d+)\**건', text):
    defer += int(m.group(1))
for m in re.finditer(r'기각\s*\**(\d+)\**건', text):
    reject += int(m.group(1))

warn = []
if maxround >= 3:
    warn.append('라운드 %d 도달 (상한 2). 검증 라운드가 새 설계 리뷰로 번지지 않았는지 확인한다' % maxround)
if maxround >= 2 and (defer + reject) == 0:
    warn.append('라운드 %d 까지 갔는데 이월과 기각이 0건이다. 전 항목 일괄 반영은 범위 증가의 직접 원인이다' % maxround)
total = reflect + defer + reject
if total >= 10 and reflect == total:
    warn.append('처분 %d건이 전부 반영이다. P1 은 항목별 판정 대상이지 일괄 반영 대상이 아니다' % total)

if warn:
    print('warnings')
    for w in warn:
        print('  %s' % w)
    print('  집계: 리뷰 블록 %d개 · 최대 라운드 %d · 반영 %d / 이월 %d / 기각 %d'
          % (blocks, maxround, reflect, defer, reject))
else:
    print('ok')
PY
}
