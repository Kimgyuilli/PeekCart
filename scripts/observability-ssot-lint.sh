#!/usr/bin/env bash
# observability-ssot-lint.sh — D5-V1 + D5-V2 정적 lint (ADR-0009 §Decision)
#
# D5-V1: SSOT 위치 위반 — base application.yml 가 SSOT 인 키
#        (management.metrics.tags.application, management.endpoints.web.exposure.include)
#        가 다른 프로파일 yaml 에 재선언됐는지 검출.
# D5-V2: 중복 재선언/복제 — MetricsConfig.java 외 다른 클래스의
#        MeterRegistryCustomizer / MeterFilter, 그리고 management.metrics.tags.application
#        의 값 불일치 ("peekcart" 외) 검출.
#
# 화이트리스트 (ADR-0007 / ADR-0009 §회색지대 분류):
#   - management.endpoint.health.probes.enabled
#   - management.endpoint.health.show-details
#   - logging.level.*
#   - spring.jpa.show-sql / format_sql
# 화이트리스트 변경 시 ADR-0007/0009 갱신 의무.
#
# Exit:
#   0 — 위반 0건
#   1 — 위반 1건 이상

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

VIOLATIONS=0

# pyyaml preflight (CI 는 ci.yml step 으로 설치, 로컬은 venv/pip 권장)
if ! python3 -c 'import yaml' 2>/dev/null; then
    echo "[lint] pyyaml 미설치 — \`python3 -m pip install --user pyyaml\` 필요" >&2
    exit 2
fi

# ---------- D5-V1 + D5-V2 (yaml side) ----------
# `set -euo pipefail` 하에서 inline python3 의 비-0 종료가 즉시 스크립트를 종료시켜
# Java side 검사가 누락되는 것을 막기 위해 `|| PY_RC=$?` 로 exit code 를 보존한다.
PY_RC=0
python3 - <<'PY' || PY_RC=$?
import sys, glob, os
import yaml

BASE = "src/main/resources/application.yml"
PROFILES = sorted(glob.glob("src/main/resources/application-*.yml"))

# ADR-0009 §Decision S2/S3: base SSOT keys
SSOT_KEYS = [
    ("management", "metrics", "tags", "application"),
    ("management", "endpoints", "web", "exposure", "include"),
]
EXPECTED_APPLICATION_TAG = "peekcart"

def get_path(d, path):
    cur = d
    for k in path:
        if not isinstance(cur, dict) or k not in cur:
            return (False, None)
        cur = cur[k]
    return (True, cur)

def load(p):
    with open(p) as f:
        return yaml.safe_load(f) or {}

violations = []

base_doc = load(BASE)
# D5-V1: SSOT key 가 다른 프로파일에 재선언?
for key in SSOT_KEYS:
    base_present, _ = get_path(base_doc, key)
    if not base_present:
        violations.append(
            f"[D5-V1] SSOT key absent in base:\n"
            f"  key: {'.'.join(key)}\n"
            f"  expected: {BASE}\n"
            f"  → ADR-0009 §Decision S2/S3: base application.yml SSOT 필수.\n"
        )
        continue
    offenders = []
    for p in PROFILES:
        prof_doc = load(p)
        present, _ = get_path(prof_doc, key)
        if present:
            offenders.append(p)
    if offenders:
        violations.append(
            f"[D5-V1] SSOT location violation:\n"
            f"  key: {'.'.join(key)}\n"
            f"  base SSOT: {BASE}\n"
            f"  violating files: {', '.join(offenders)}\n"
            f"  → ADR-0009 §Decision S2/S3: base application.yml SSOT only.\n"
        )

# D5-V2 (yaml side): application 태그 값이 'peekcart' 외 값인 파일
APP_TAG_PATH = ("management", "metrics", "tags", "application")
all_files = [BASE] + PROFILES
for p in all_files:
    doc = load(p)
    present, val = get_path(doc, APP_TAG_PATH)
    if present and val != EXPECTED_APPLICATION_TAG:
        violations.append(
            f"[D5-V2] application tag value mismatch:\n"
            f"  file: {p}\n"
            f"  expected: {EXPECTED_APPLICATION_TAG!r}\n"
            f"  found:    {val!r}\n"
            f"  → ADR-0009 §Decision S2: 단일 값 'peekcart'.\n"
        )

if violations:
    sys.stdout.write("\n".join(violations) + "\n")
    sys.exit(1)
sys.exit(0)
PY
if [[ "$PY_RC" -eq 2 ]]; then
    exit 2
fi
if [[ "$PY_RC" -ne 0 ]]; then
    VIOLATIONS=$((VIOLATIONS + 1))
fi

# ---------- D5-V2 (Java side): MeterFilter / MeterRegistryCustomizer 중복 ----------
EXPECTED_OWNER="src/main/java/com/peekcart/global/config/MetricsConfig.java"

# MeterFilter / MeterRegistryCustomizer 식별자를 사용하는 .java 파일 광범위 grep.
# narrow 패턴 (`new MeterFilter()`, `MeterRegistryCustomizer<`) 만 보면
# `@Bean MeterFilter foo() { return MeterFilter.deny(...); }` 같은 factory 방식을 놓침.
# import-only false positive 는 후속 grep 으로 필터링.
mapfile -t CANDIDATES < <(grep -rln -E '\b(MeterFilter|MeterRegistryCustomizer)\b' src/main/java 2>/dev/null || true)

OFFENDERS=()
for f in "${CANDIDATES[@]:-}"; do
    [[ -z "$f" ]] && continue
    [[ "$f" == "$EXPECTED_OWNER" ]] && continue
    # import 라인을 제외한 식별자 사용 라인이 1건 이상 있는지 확인 (import-only 제외)
    non_import=$(grep -nE '\b(MeterFilter|MeterRegistryCustomizer)\b' "$f" \
                 | grep -vE '^\s*[0-9]+:\s*import\s' || true)
    if [[ -n "$non_import" ]]; then
        OFFENDERS+=("$f")
    fi
done

if [[ ${#OFFENDERS[@]} -gt 0 ]]; then
    {
        echo "[D5-V2] Duplicate declaration:"
        echo "  surface: S1 (MeterRegistryCustomizer / MeterFilter)"
        echo "  expected single owner: $EXPECTED_OWNER"
        echo "  also found in:"
        for o in "${OFFENDERS[@]}"; do
            echo "    - $o"
        done
        echo "  → ADR-0009 §Decision S1: 이동·복제 금지."
    } >&2
    VIOLATIONS=$((VIOLATIONS + 1))
fi

if [[ "$VIOLATIONS" -gt 0 ]]; then
    exit 1
fi
exit 0
