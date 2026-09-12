#!/usr/bin/env bash
# migration-grant-lint — Flyway 마이그레이션이 **부여되지 않은 권한**을 요구하는지 정적 검사
#
# 무엇을 막는가 (실제로 났던 사고 — PR #105):
#   ④-c-2b-4a 의 backfill 마이그레이션이 "잔여 0 검증" 을 `CREATE PROCEDURE` + `SIGNAL` 로 구현했다.
#   Testcontainers 는 **root** 로 돌아 전 모듈 1130 테스트가 green 이었지만, 실제 스택의 서비스 계정에는
#   `CREATE ROUTINE` 이 없어 `ERROR 1370: alter routine command denied` 로 **4서비스가 전부 부팅에 실패**했다.
#   테스트가 도는 권한과 운영이 도는 권한이 다르면, 테스트는 그 차이를 영원히 보지 못한다.
#
# 정본은 GRANT 문이다:
#   권한 목록을 하드코딩하지 않고 `scripts/mysql-init/01-init-databases.sql` 에서 **파싱**한다 —
#   하드코딩하면 GRANT 가 넓어지거나 좁아져도 이 검사가 따라가지 않는다.
#
# 사용: bash scripts/migration-grant-lint.sh [--self-test]
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

GRANT_FILE="scripts/mysql-init/01-init-databases.sql"
LINT_PY="$(mktemp -t migration-grant-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import glob, os, re, sys

root = sys.argv[1]
grant_file = os.path.join(root, "scripts/mysql-init/01-init-databases.sql")
violations = []

# --- GRANT 문에서 부여된 권한 집합을 유도한다 ---
granted = set()
if not os.path.exists(grant_file):
    violations.append("[MIG-GRANT-001] GRANT 정본 파일이 없다: scripts/mysql-init/01-init-databases.sql")
else:
    with open(grant_file, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"\s*GRANT\s+(.+?)\s+ON\s", line, re.IGNORECASE)
            if m:
                for priv in m.group(1).split(","):
                    granted.add(priv.strip().upper())

# 마이그레이션 구문 → 그 구문이 요구하는 권한.
# 여기 없는 구문은 검사하지 않는다 — 모르는 것을 금지하면 lint 가 개발을 막는다.
REQUIREMENTS = [
    (r"\bCREATE\s+(DEFINER\s*=\s*\S+\s+)?PROCEDURE\b", "CREATE ROUTINE", "stored procedure"),
    (r"\bCREATE\s+(DEFINER\s*=\s*\S+\s+)?FUNCTION\b",  "CREATE ROUTINE", "stored function"),
    (r"\bDROP\s+PROCEDURE\b",                          "ALTER ROUTINE",  "stored procedure 삭제"),
    (r"\bDROP\s+FUNCTION\b",                           "ALTER ROUTINE",  "stored function 삭제"),
    (r"\bCALL\s+\w+\s*\(",                             "EXECUTE",        "stored procedure 호출"),
    (r"\bCREATE\s+(DEFINER\s*=\s*\S+\s+)?TRIGGER\b",   "TRIGGER",        "트리거"),
    (r"\bCREATE\s+(DEFINER\s*=\s*\S+\s+)?EVENT\b",     "EVENT",          "이벤트 스케줄러"),
    (r"\bCREATE\s+(OR\s+REPLACE\s+)?(DEFINER\s*=\s*\S+\s+)?(SQL\s+SECURITY\s+\w+\s+)?VIEW\b",
                                                       "CREATE VIEW",    "뷰"),
    (r"\bCREATE\s+TEMPORARY\s+TABLE\b",                "CREATE TEMPORARY TABLES", "임시 테이블"),
    (r"\bLOCK\s+TABLES\b",                             "LOCK TABLES",    "테이블 잠금"),
    (r"\bSET\s+GLOBAL\b",                              "SYSTEM_VARIABLES_ADMIN", "전역 변수 설정"),
    (r"^\s*GRANT\b",                                   "GRANT OPTION",   "권한 부여"),
]

def strip_sql_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return "\n".join(re.sub(r"--.*$", "", line) for line in text.splitlines())

migrations = sorted(glob.glob(os.path.join(root, "*/src/main/resources/db/migration/V*.sql")))
if not migrations:
    violations.append("[MIG-GRANT-002] 검사할 마이그레이션을 찾지 못했다 (glob 이 비었다)")

for path in migrations:
    with open(path, encoding="utf-8") as f:
        body = strip_sql_comments(f.read())
    rel = os.path.relpath(path, root)
    for pattern, required, label in REQUIREMENTS:
        if re.search(pattern, body, re.IGNORECASE | re.MULTILINE) and required not in granted:
            violations.append(
                "[MIG-GRANT-003] %s: %s 를 쓰는데 서비스 계정에 `%s` 권한이 없다\n"
                "    부여된 권한: %s\n"
                "    → 실제 스택에서 부팅이 깨진다. Testcontainers 는 root 라 이 차이를 보지 못한다."
                % (rel, label, required, ", ".join(sorted(granted)) or "(없음)"))

if violations:
    print("migration-grant-lint 위반:")
    for v in violations:
        print("  " + v)
    sys.exit(1)

print("migration-grant-lint OK — 마이그레이션 %d개가 부여된 권한(%s) 안에서만 동작한다"
      % (len(migrations), ", ".join(sorted(granted))))
PYEOF

if [[ "${1:-}" == "--self-test" ]]; then
    TMP="$(mktemp -d -t migration-grant-selftest.XXXXXX)"
    trap 'rm -rf "$TMP"; rm -f "$LINT_PY"' EXIT
    mkdir -p "$TMP/scripts/mysql-init" "$TMP/svc-service/src/main/resources/db/migration"
    cp "$GRANT_FILE" "$TMP/scripts/mysql-init/01-init-databases.sql"

    # 1: 부여된 권한만 쓰는 마이그레이션 → 통과
    cat > "$TMP/svc-service/src/main/resources/db/migration/V1__ok.sql" <<'EOS'
ALTER TABLE t ADD COLUMN c INT NULL;
UPDATE t SET c = 1 WHERE c IS NULL;
SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',STRICT_ALL_TABLES');
EOS
    python3 "$LINT_PY" "$TMP" >/dev/null || { echo "self-test 1 실패: 정상 마이그레이션을 위반으로 판정"; exit 1; }

    # 2~5: 권한 없는 구문 각각 검출되어야 한다 (PR #105 에서 실제로 난 것 포함)
    for stmt in "CREATE PROCEDURE p() BEGIN SELECT 1; END" \
                "DROP PROCEDURE IF EXISTS p" \
                "CREATE TEMPORARY TABLE tmp (a INT)" \
                "CREATE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW SET @x = 1"; do
        printf '%s;\n' "$stmt" > "$TMP/svc-service/src/main/resources/db/migration/V2__bad.sql"
        if python3 "$LINT_PY" "$TMP" >/dev/null 2>&1; then
            echo "self-test 실패: 검출하지 못했다 — $stmt"; exit 1
        fi
    done
    rm "$TMP/svc-service/src/main/resources/db/migration/V2__bad.sql"

    # 6: 주석 안의 금지 구문은 위반이 아니다 (이 레포의 마이그레이션은 주석에 사유를 길게 적는다)
    cat > "$TMP/svc-service/src/main/resources/db/migration/V3__comment.sql" <<'EOS'
-- stored procedure + SIGNAL 을 쓰지 않는다: CREATE PROCEDURE 는 권한이 없다
/* CREATE TEMPORARY TABLE 도 마찬가지다 */
UPDATE t SET c = 1 WHERE c IS NULL;
EOS
    python3 "$LINT_PY" "$TMP" >/dev/null || { echo "self-test 6 실패: 주석 안 구문을 위반으로 오판"; exit 1; }

    echo "migration-grant-lint self-test 6종 통과"
    exit 0
fi

python3 "$LINT_PY" "$(pwd)"
