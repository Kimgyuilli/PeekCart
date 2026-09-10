#!/usr/bin/env bash
# dead-letter-schema-parity-lint — 4서비스 dead_letter_records 스키마 동일성 (구현 ④-c-2a P10)
#
# 무엇을 막는가:
#   DLQ 원장은 DB-per-service(ADR-0012 D1) 때문에 order/product/payment/notification 각자의 DB 에
#   같은 테이블을 둔다. 한 서비스에서만 컬럼을 고치면 runbook 의 조회 절차가 그 서비스에서만 깨지고,
#   깨진 사실은 운영자가 그 DB 를 볼 때까지 드러나지 않는다.
#
# 왜 테스트가 아니라 lint 인가:
#   테이블이 4개 모듈에 흩어져 있어 한 모듈의 테스트가 다른 모듈의 마이그레이션을 볼 수 없다.
#   비교 대상이 소스 파일이므로 정적 검사가 맞다.
#
# 무엇을 비교하는가:
#   (1) CREATE TABLE dead_letter_records (...) 본문과 인덱스 정의. 주석·공백은 무시한다.
#   (2) ALTER TABLE dead_letter_records 후속 마이그레이션 (④-c-2b-1 P1 replay 축) — glob 을 넓히지 않으면
#       신규 파일이 검사에 걸리지 않아 4벌이 갈라져도 아무 것도 실패하지 않는다.
#   (3) global/deadletter java 파일 (④-c-2b-1 P1-b) — 4서비스에 byte 동일 복제이며, 한 벌만 고치는 실수를
#       스키마와 같은 이유로 막아야 한다. 원장 로직이 서비스마다 달라지면 runbook 절차가 한 곳에서만 깨진다.
#   (4) outbox_events **최종 스키마** + global/outbox java (④-c-2b-2 P9-b) — replay 재발행이 4서비스 공통
#       경로가 되면서 outbox 도 원장과 같은 복제 자산이 됐다. 여기는 **원문 해시로 대조할 수 없다**:
#       order/product/payment 는 V1 의 CREATE TABLE + P8 의 ALTER 로 만들어지고 notification 은 P9 의
#       CREATE TABLE 한 방으로 만들어진다 — 생성 경로가 다르므로 "컬럼명 → 정의" 집합으로 정규화해 비교한다.
#
# 사용: bash scripts/dead-letter-schema-parity-lint.sh [--self-test]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

LINT_PY="$(mktemp -t dead-letter-schema-parity-lint.XXXXXX.py)"
trap 'rm -f "$LINT_PY"' EXIT

cat > "$LINT_PY" <<'PYEOF'
import glob
import hashlib
import os
import re
import sys

root = sys.argv[1]
services = ["order-service", "product-service", "payment-service", "notification-service"]

violations = []


def normalize(sql):
    """주석·공백을 제거해 의미 있는 DDL 만 남긴다."""
    out = []
    for line in sql.splitlines():
        line = re.sub(r"--.*$", "", line).strip()
        if line:
            out.append(re.sub(r"\s+", " ", line))
    return "\n".join(out)


def extract(path):
    with open(path, encoding="utf-8") as f:
        sql = f.read()
    body = normalize(sql)
    if "CREATE TABLE dead_letter_records" not in body:
        return None
    return body


def table_columns(service, table):
    """서비스의 <table> 최종 컬럼 정의를 {컬럼명: 정의} 로 돌려준다. CREATE 를 못 찾으면 None.

    **파일명이 아니라 db/migration 전체를 번호 순으로 훑는다.** glob 으로 특정 파일만 보면 신규
    마이그레이션이 같은 테이블을 건드려도 검사에 들어오지 않는다 — ④-c-2b-1(glob 미확장),
    ④-c-2b-2(java 목록 누락), ④-c-2b-3(replay 축 glob) 에서 연속으로 난 구멍이다.
    """
    paths = sorted(
        glob.glob(os.path.join(root, service, "src/main/resources/db/migration/V*.sql")),
        key=lambda x: int(re.match(r"V(\d+)__", os.path.basename(x)).group(1)))
    columns = {}
    seen_create = False
    for path in paths:
        with open(path, encoding="utf-8") as f:
            body = normalize(f.read())

        m = re.search(r"CREATE TABLE %s \((.*?)\n\)" % re.escape(table), body, re.S)
        if m:
            seen_create = True
            for line in m.group(1).splitlines():
                line = line.strip().rstrip(",").strip()
                if not line or line.upper().startswith(("CONSTRAINT", "PRIMARY KEY", "UNIQUE", "KEY ", "INDEX")):
                    continue
                parts = line.split(None, 1)
                if len(parts) == 2:
                    columns[parts[0]] = re.sub(r"\s+", " ", parts[1]).upper()

        for alter in re.finditer(r"ALTER TABLE %s(.*?);" % re.escape(table), body, re.S):
            for line in alter.group(1).splitlines():
                line = line.strip().rstrip(",").strip()
                m2 = re.match(r"ADD COLUMN\s+(\S+)\s+(.*)$", line)
                if m2:
                    # AFTER <col> 은 물리 배치일 뿐 계약이 아니다 — 서비스마다 컬럼 순서가 달라도
                    # 스키마는 같으므로 정의에서 떼어낸다.
                    definition = re.sub(r"\s+AFTER\s+\S+$", "", m2.group(2).strip(), flags=re.I)
                    columns[m2.group(1)] = re.sub(r"\s+", " ", definition).strip().upper()
    return columns if seen_create else None


fingerprints = {}
for service in services:
    matches = glob.glob(os.path.join(root, service, "src/main/resources/db/migration/V*__dead_letter_records.sql"))
    if len(matches) != 1:
        violations.append(
            "[DLQ-PARITY-001] %s: dead_letter_records 마이그레이션이 %d개다 (정확히 1개여야 한다)"
            % (service, len(matches)))
        continue
    body = extract(matches[0])
    if body is None:
        violations.append("[DLQ-PARITY-002] %s: %s 에 CREATE TABLE dead_letter_records 가 없다"
                          % (service, os.path.basename(matches[0])))
        continue
    fingerprints[service] = (os.path.basename(matches[0]), hashlib.sha256(body.encode()).hexdigest(), body)

if len(fingerprints) == len(services):
    digests = {d for _, d, _ in fingerprints.values()}
    if len(digests) != 1:
        violations.append("[DLQ-PARITY-003] 4서비스의 dead_letter_records 스키마가 다르다:")
        for service, (fname, digest, _) in sorted(fingerprints.items()):
            violations.append("    %-22s %s  %s" % (service, digest[:12], fname))
        # 첫 서비스를 기준으로 차이 나는 줄을 보여준다
        baseline_service = services[0]
        baseline = fingerprints[baseline_service][2].splitlines()
        for service, (_, digest, body) in sorted(fingerprints.items()):
            if service == baseline_service:
                continue
            lines = body.splitlines()
            only_here = [l for l in lines if l not in baseline]
            only_there = [l for l in baseline if l not in lines]
            if only_here or only_there:
                violations.append("    --- %s vs %s" % (service, baseline_service))
                for l in only_here:
                    violations.append("      + %s" % l)
                for l in only_there:
                    violations.append("      - %s" % l)

# UNIQUE 키가 6컬럼인지 — 여기가 무너지면 poison record 가 여러 행이 된다
for service, (fname, _, body) in sorted(fingerprints.items()):
    m = re.search(r"CONSTRAINT uk_dead_letter_records_origin UNIQUE \(([^)]*)\)", body)
    if not m:
        violations.append("[DLQ-PARITY-004] %s: uk_dead_letter_records_origin UNIQUE 제약이 없다" % service)
        continue
    columns = [c.strip() for c in m.group(1).split(",") if c.strip()]
    expected = ["cluster_id", "topic_generation", "origin_topic",
                "origin_partition", "origin_offset", "failed_consumer_group"]
    if columns != expected:
        violations.append("[DLQ-PARITY-005] %s: UNIQUE 컬럼이 계약과 다르다\n    기대: %s\n    실제: %s"
                          % (service, expected, columns))

# 식별자 6컬럼은 전부 NOT NULL — nullable 이면 MySQL UNIQUE 가 중복을 막지 못한다
for service, (fname, _, body) in sorted(fingerprints.items()):
    for column in ["cluster_id", "topic_generation", "origin_topic",
                   "origin_partition", "origin_offset", "failed_consumer_group"]:
        m = re.search(r"^%s\s+[A-Z]+(\([0-9]+\))?\s+(NOT NULL)" % re.escape(column), body, re.M)
        if not m:
            violations.append("[DLQ-PARITY-006] %s: 식별자 컬럼 %s 가 NOT NULL 이 아니다 "
                              "(nullable 이면 UNIQUE 가 중복을 막지 못한다)" % (service, column))

# --- (2) replay 축 ALTER 마이그레이션 parity (④-c-2b-1 P1) ---
alter_prints = {}
for service in services:
    matches = glob.glob(os.path.join(root, service, "src/main/resources/db/migration/V*__dead_letter_replay_axis.sql"))
    if len(matches) != 1:
        violations.append(
            "[DLQ-PARITY-007] %s: dead_letter_replay_axis 마이그레이션이 %d개다 (정확히 1개여야 한다)"
            % (service, len(matches)))
        continue
    # **바이너리로 읽어 그대로 해시한다.** text mode 로 읽으면 Python 이 universal-newline 변환을 해서
    # LF 와 CRLF 파일이 같은 해시가 된다 — "원문 바이트 동일" 을 표방하면서 개행 drift 를 통과시킨다.
    with open(matches[0], "rb") as f:
        raw_bytes = f.read()
    body = normalize(raw_bytes.decode("utf-8"))
    if "ALTER TABLE dead_letter_records" not in body:
        violations.append("[DLQ-PARITY-008] %s: %s 에 ALTER TABLE dead_letter_records 가 없다"
                          % (service, os.path.basename(matches[0])))
        continue
    # **원문 바이트로 해시한다.** 이 파일들은 한 벌을 복사해 만든 사본이므로 주석·공백 차이도 drift 다
    # (normalize 해시는 주석이 갈라져도 통과한다). CREATE TABLE 쪽은 ④-c-2a 가 정한 정규화 비교를 유지한다.
    alter_prints[service] = (os.path.basename(matches[0]), hashlib.sha256(raw_bytes).hexdigest(), body)

if len(alter_prints) == len(services):
    digests = {d for _, d, _ in alter_prints.values()}
    if len(digests) != 1:
        violations.append("[DLQ-PARITY-009] 4서비스의 replay 축 마이그레이션이 다르다 (주석·공백 포함 원문 대조):")
        for service, (fname, digest, _) in sorted(alter_prints.items()):
            violations.append("    %-22s %s  %s" % (service, digest[:12], fname))

    # replay 축 컬럼은 **전부 nullable** 이어야 한다 — NOT NULL 을 먼저 걸면 롤링 배포 중 구버전 INSERT 가 깨지고,
    # root_record_id 의 expand→backfill→contract 순서(ADR-0020 D6-3)가 성립하지 않는다.
    # 서비스마다 검사한다 — 기준 1벌만 보면 변이가 다른 서비스에 있을 때 이 검사가 새 나간다.
    for service, (_, _, body) in sorted(alter_prints.items()):
        for column in ["root_record_id", "publication_status", "outbox_event_id",
                       "last_replay_attempt_id", "last_replay_target_group", "replay_deadline",
                       "replay_policy", "resolved_at", "resolved_by", "reopened_at", "reopened_reason"]:
            m = re.search(r"ADD COLUMN %s\s+[A-Z]+(\([0-9]+\))?\s+(NOT NULL|NULL)" % re.escape(column), body)
            if not m:
                violations.append("[DLQ-PARITY-010] %s: replay 축 컬럼 %s 가 마이그레이션에 없다" % (service, column))
            elif m.group(2) != "NULL":
                violations.append("[DLQ-PARITY-011] %s: replay 축 컬럼 %s 가 nullable 이 아니다 "
                                  "(롤링 배포 중 구버전 INSERT 가 깨지고 expand→backfill 순서가 성립하지 않는다)"
                                  % (service, column))

# --- (2b) dead_letter_records **최종 스키마** parity (④-c-2b-3a P14 f-2) ---
#
# 왜 (2) 로 부족한가:
#   (2) 는 V*__dead_letter_replay_axis.sql **한 파일**만 본다. 그 뒤에 같은 테이블을 건드리는 마이그레이션이
#   새로 생기면 파일명이 달라 glob 에 걸리지 않고, 컬럼 목록도 하드코딩이라 **본실행도 self-test 도 green**
#   이다 — ④-c-2b-1(glob 미확장) · ④-c-2b-2(java 목록 누락) 에 이은 같은 구멍의 세 번째다.
#   그래서 파일 단위가 아니라 **db/migration 전체를 합성한 최종 스키마**를 대조한다.
#   (2) 는 지우지 않는다 — 그쪽은 "4벌이 서로의 사본" 이라는 더 강한 성질(주석·공백 포함 원문 동일)을 지킨다.
dlr_schemas = {}
for service in services:
    columns = table_columns(service, "dead_letter_records")
    if columns is None:
        violations.append("[DLQ-PARITY-015] %s: CREATE TABLE dead_letter_records 가 없다" % service)
        continue
    dlr_schemas[service] = columns

if len(dlr_schemas) == len(services):
    baseline_service = services[0]
    baseline = dlr_schemas[baseline_service]
    for service, columns in sorted(dlr_schemas.items()):
        if service == baseline_service or columns == baseline:
            continue
        violations.append("[DLQ-PARITY-016] %s 의 dead_letter_records 최종 스키마가 %s 와 다르다:"
                          % (service, baseline_service))
        for name in sorted(set(columns) | set(baseline)):
            here, there = columns.get(name), baseline.get(name)
            if here != there:
                violations.append("    %-28s %s  vs  %s" % (name, here, there))

# replay 상관 앵커는 **전부 nullable · DEFAULT 없음** 이어야 한다.
#   nullable: 롤링 배포 중 구버전 writer 의 INSERT 를 살린다.
#   DEFAULT 부재: "값을 안 썼다" 와 "빈 값" 이 같아 보이면 판별자 누락이 조용히 삼켜진다(ADR-0020 D3).
# 타입까지 못박는 이유: digest 는 SHA-256 hex 라 64자다. 짧으면 잘려 저장되고, 잘린 digest 는
#   서로 다른 payload 를 같다고 말한다 — 오상관 방지를 표방하면서 정확히 그 반대를 한다.
ANCHOR_COLUMNS = {
    "last_replay_attempt_id": "VARCHAR(36)",
    "last_replay_target_group": "VARCHAR(120)",
    "last_replay_payload_digest": "VARCHAR(64)",   # ④-c-2b-3a P14, ADR-0021 D1
}
for service, columns in sorted(dlr_schemas.items()):
    for column, expected_type in ANCHOR_COLUMNS.items():
        definition = columns.get(column)
        if definition is None:
            violations.append("[DLQ-PARITY-017] %s: 상관 앵커 컬럼 %s 가 최종 스키마에 없다 "
                              "(없으면 재실패가 root 로 수렴하지 못하고 독립 incident 로 갈라진다)"
                              % (service, column))
            continue
        if not definition.startswith(expected_type.upper()):
            violations.append("[DLQ-PARITY-018] %s: %s 의 타입이 %s 가 아니다 — %s"
                              % (service, column, expected_type, definition))
        if "NOT NULL" in definition:
            violations.append("[DLQ-PARITY-019] %s: %s 가 NOT NULL 이다 "
                              "(롤링 배포 중 구버전 INSERT 가 깨진다)" % (service, column))
        if "DEFAULT" in definition:
            violations.append("[DLQ-PARITY-020] %s: %s 에 DEFAULT 가 있다 "
                              "(기본값은 앵커 미기록을 조용히 삼킨다 — ADR-0020 D3)" % (service, column))

# --- (3) global/deadletter java 복제 parity (④-c-2b-1 P1-b) ---
java_files = ["DeadLetterRecord", "DeadLetterStatus", "PublicationStatus", "DeadLetterRecorder",
              "DeadLetterRecordJpaRepository", "DeadLetterEndpoint", "DeadLetterMetrics",
              "DeadLetterMaintenanceScheduler", "DeadLetterTransitionService",
              # ④-c-2b-2 P12. **신규 복제 파일은 이 목록에 반드시 더한다** — 목록에 없으면 4벌이 갈라져도
              # 아무 것도 실패하지 않는다(④-c-2b-1 이 glob 을 안 넓혀 겪은 것과 같은 구멍이다).
              "DeadLetterPublicationReconciler",
              # ④-c-2a 산출물이나 목록에 빠져 있던 복제본 2개. 아래 DLQ-PARITY-014 검사가 찾아냈다 —
              # 사람이 목록을 관리하는 한 누락은 반복되므로, 목록 자체를 검사가 지킨다.
              "DeadLetterContainerGuard", "DeadLetterKafkaConfig"]
# **목록 자체가 디렉토리와 일치하는지 먼저 본다.** 신규 복제 파일을 만들고 목록에 더하는 것을 잊으면
#   그 파일만 무방비가 되는데, 그 사실은 아무 것도 실패시키지 않아 드러나지 않는다 — ④-c-2b-1(glob 미확장)과
#   ④-c-2b-2(reconciler 목록 누락)에서 연속으로 났다. 사람의 기억이 아니라 디렉토리를 정본으로 삼는다.
#   **디렉토리 전체를 요구하지는 않는다** — global/deadletter 에는 서비스마다 정당하게 다른 파일이 있다
#   (DeadLetterConsumer/KafkaConfig/QuarantineConsumer 는 토픽·group 이 서비스별로 다르다).
#   판별 기준은 "지금 4벌이 byte 동일한데 목록에 없는 파일" 이다 — 그것이 복제 자산의 정의이고,
#   서비스별로 달라야 하는 파일은 애초에 동일하지 않아 여기 걸리지 않는다.
baseline_dir = os.path.join(root, services[0], "src/main/java/com/peekcart/global/deadletter")
if os.path.isdir(baseline_dir):
    for path in sorted(glob.glob(os.path.join(baseline_dir, "*.java"))):
        name = os.path.basename(path)[:-5]
        if name in java_files:
            continue
        copies = []
        for service in services:
            candidate = os.path.join(root, service, "src/main/java/com/peekcart/global/deadletter", name + ".java")
            if not os.path.exists(candidate):
                break
            with open(candidate, "rb") as f:
                copies.append(hashlib.sha256(f.read()).hexdigest())
        if len(copies) == len(services) and len(set(copies)) == 1:
            violations.append(
                "[DLQ-PARITY-014] %s.java 는 4서비스가 byte 동일한 복제본인데 java_files 목록에 없다 "
                "— 목록에 없으면 이후 4벌이 갈라져도 아무 것도 실패하지 않는다" % name)

for name in java_files:
    digests = {}
    for service in services:
        path = os.path.join(root, service, "src/main/java/com/peekcart/global/deadletter", name + ".java")
        if not os.path.exists(path):
            violations.append("[DLQ-PARITY-012] %s: %s.java 가 없다" % (service, name))
            continue
        with open(path, "rb") as f:   # 개행 drift 를 놓치지 않도록 바이너리로 읽는다
            digests[service] = hashlib.sha256(f.read()).hexdigest()
    if len(digests) == len(services) and len({d for d in digests.values()}) != 1:
        violations.append("[DLQ-PARITY-013] %s.java 가 4서비스에서 동일하지 않다:" % name)
        for service, digest in sorted(digests.items()):
            violations.append("    %-22s %s" % (service, digest[:12]))

# --- (4) outbox_events 최종 스키마 parity (④-c-2b-2 P9-b) ---
#
# 생성 경로가 서비스마다 다르므로(3서비스 = CREATE + ALTER · notification = CREATE 단독) 원문이 아니라
# **컬럼명 → 정의 문자열** 집합으로 비교한다. 파일명에 의존하지 않고 db/migration 전체를 훑는다 —
# 신규 마이그레이션이 outbox_events 를 건드려도 자동으로 검사에 들어온다(④-c-2b-1 의 glob 교훈).
REPLAY_OUTBOX_COLUMNS = ["record_kind", "destination_topic", "destination_partition", "record_key",
                         "source_record_timestamp", "replay_target_event_id", "replay_headers",
                         "replay_root_record_id", "target_consumer_group"]


outbox_schemas = {}
for service in services:
    columns = table_columns(service, "outbox_events")
    if columns is None:
        violations.append("[OUTBOX-PARITY-001] %s: CREATE TABLE outbox_events 가 없다 "
                          "(replay 재발행은 4서비스 공통 경로다 — ADR-0020 D2)" % service)
        continue
    outbox_schemas[service] = columns

if len(outbox_schemas) == len(services):
    baseline_service = services[0]
    baseline = outbox_schemas[baseline_service]
    for service, columns in sorted(outbox_schemas.items()):
        if service == baseline_service or columns == baseline:
            continue
        violations.append("[OUTBOX-PARITY-002] %s 의 outbox_events 최종 스키마가 %s 와 다르다:"
                          % (service, baseline_service))
        for name in sorted(set(columns) | set(baseline)):
            here, there = columns.get(name), baseline.get(name)
            if here != there:
                violations.append("    %-24s %s  vs  %s" % (name, here, there))

    # replay 축 9컬럼: 4서비스 전부에 존재 · 전부 nullable · DEFAULT 없음 (ADR-0020 D3)
    #   DEFAULT 를 두면 신버전 writer 가 record_kind 를 빠뜨려도 DB 가 조용히 DOMAIN 으로 분류한다 —
    #   누락을 실패시키려던 명시적 kind 계약이 그 자리에서 약해진다.
    for service, columns in sorted(outbox_schemas.items()):
        for name in REPLAY_OUTBOX_COLUMNS:
            definition = columns.get(name)
            if definition is None:
                violations.append("[OUTBOX-PARITY-003] %s: replay 축 컬럼 %s 가 없다" % (service, name))
                continue
            if "NOT NULL" in definition:
                violations.append("[OUTBOX-PARITY-004] %s: replay 축 컬럼 %s 가 nullable 이 아니다 "
                                  "(롤링 배포 중 구버전 INSERT 가 깨진다)" % (service, name))
            if "DEFAULT" in definition:
                violations.append("[OUTBOX-PARITY-005] %s: replay 축 컬럼 %s 에 DEFAULT 가 있다 "
                                  "(판별자 누락이 조용히 DOMAIN 으로 분류된다 — ADR-0020 D3)" % (service, name))

# global/outbox java 복제 parity. OutboxEventStatus 만 **2집합** 계약이다 —
#   order/product 는 BACKFILL(④-c-1b backfill 이 쓰는 조립 중 상태)을 갖고 payment/notification 은 갖지 않는다.
#   "4서비스 전부 동일" 로 걸면 기존 상태가 곧바로 red 다.
outbox_java = ["OutboxEvent", "OutboxRecordKind", "OutboxEventJpaRepository", "OutboxEventRepository",
               "OutboxEventRepositoryImpl", "OutboxEventCleanupScheduler", "OutboxPollingScheduler",
               "OutboxPollingService"]
for name in outbox_java:
    digests = {}
    for service in services:
        path = os.path.join(root, service, "src/main/java/com/peekcart/global/outbox", name + ".java")
        if not os.path.exists(path):
            violations.append("[OUTBOX-PARITY-006] %s: %s.java 가 없다" % (service, name))
            continue
        with open(path, "rb") as f:
            digests[service] = hashlib.sha256(f.read()).hexdigest()
    if len(digests) == len(services) and len(set(digests.values())) != 1:
        violations.append("[OUTBOX-PARITY-007] %s.java 가 4서비스에서 동일하지 않다:" % name)
        for service, digest in sorted(digests.items()):
            violations.append("    %-22s %s" % (service, digest[:12]))

status_digests = {}
for service in services:
    path = os.path.join(root, service, "src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java")
    if not os.path.exists(path):
        violations.append("[OUTBOX-PARITY-006] %s: OutboxEventStatus.java 가 없다" % service)
        continue
    with open(path, "rb") as f:
        status_digests[service] = hashlib.sha256(f.read()).hexdigest()
if len(status_digests) == len(services):
    for group in (["order-service", "product-service"], ["payment-service", "notification-service"]):
        if len({status_digests[svc] for svc in group}) != 1:
            violations.append("[OUTBOX-PARITY-008] OutboxEventStatus.java 가 %s 안에서 다르다 "
                              "(BACKFILL 보유 여부로 나뉜 2집합 계약)" % " / ".join(group))
    if status_digests["order-service"] == status_digests["payment-service"]:
        violations.append("[OUTBOX-PARITY-009] OutboxEventStatus.java 의 두 집합이 같아졌다 — "
                          "BACKFILL 이 payment/notification 에 새거나 order/product 에서 사라졌다")

if violations:
    print("dead-letter-schema-parity-lint 위반:")
    for v in violations:
        print("  " + v)
    sys.exit(1)

print("dead-letter-schema-parity-lint OK — 4서비스 dead_letter_records 스키마·replay 축 마이그레이션·java %d파일 동일, "
      "outbox_events 최종 스키마·global/outbox java %d파일 동일(+OutboxEventStatus 2집합)"
      % (len(java_files), len(outbox_java)))
PYEOF

if [[ "${1:-}" == "--self-test" ]]; then
    TMP="$(mktemp -d)"
    trap 'rm -f "$LINT_PY"; rm -rf "$TMP"' EXIT

    seed_fixture() {
        # **먼저 지운다.** 앞 케이스가 심은 파일(예: 목록에 없는 신규 복제본)이 남으면 뒤 케이스가
        # 그것 때문에 실패한다 — 케이스 간 오염은 "무엇이 red 를 만들었는지" 를 알 수 없게 만든다.
        rm -rf "${TMP:?}"/*
        for svc in order-service product-service payment-service notification-service; do
            mkdir -p "$TMP/$svc/src/main/resources/db/migration"
            mkdir -p "$TMP/$svc/src/main/java/com/peekcart/global/deadletter"
            cp order-service/src/main/resources/db/migration/V*__dead_letter_records.sql \
               "$TMP/$svc/src/main/resources/db/migration/V1__dead_letter_records.sql"
            cp order-service/src/main/resources/db/migration/V*__dead_letter_replay_axis.sql \
               "$TMP/$svc/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
            # digest 축(④-c-2b-3a P14). **별도 파일로 심는 것이 핵심이다** — replay 축 glob 에 걸리지 않는
            # 파일이 최종 스키마 검사에는 걸린다는 것이 이 축의 존재 이유다.
            cp order-service/src/main/resources/db/migration/V*__dead_letter_replay_payload_digest.sql \
               "$TMP/$svc/src/main/resources/db/migration/V5__dead_letter_replay_payload_digest.sql"
            cp order-service/src/main/java/com/peekcart/global/deadletter/*.java \
               "$TMP/$svc/src/main/java/com/peekcart/global/deadletter/"
            # **per-service 파일은 fixture 에서도 서비스마다 달라야 한다.** 전부 order 사본으로 채우면
            # 실제로는 토픽·group 이 다른 파일들이 fixture 안에서만 byte 동일해져 DLQ-PARITY-014 가
            # 오탐한다 — fixture 가 현실을 왜곡하면 self-test 가 검사하는 대상이 현실이 아니게 된다.
            # ④-c-2b-3b P15-f 가 LedgerOwnerConfig 를 더했다 — 서비스마다 PeekcartService 값이 다르다.
            # 목록에 안 넣으면 fixture 안에서만 4벌이 byte 동일해져 DLQ-PARITY-014 가 오탐한다.
            for per_service in DeadLetterConsumer DeadLetterQuarantineConsumer LedgerOwnerConfig; do
                echo "// $svc 고유 배선 (토픽·group 이 서비스마다 다르다)" \
                    >> "$TMP/$svc/src/main/java/com/peekcart/global/deadletter/${per_service}.java"
            done

            # outbox 축 (④-c-2b-2 P9-b). **생성 경로를 실제와 같이 둘로 나눠 심는다** —
            #   3서비스는 CREATE(V1) + ALTER(V3), notification 은 CREATE 단독(V4).
            #   양쪽을 같은 파일로 심으면 "경로가 달라도 최종 스키마가 같다" 는 이 검사의 본체가 vacuous 해진다.
            mkdir -p "$TMP/$svc/src/main/java/com/peekcart/global/outbox"
            cp order-service/src/main/java/com/peekcart/global/outbox/*.java \
               "$TMP/$svc/src/main/java/com/peekcart/global/outbox/"
            cp payment-service/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java \
               "$TMP/$svc/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java"
        done
        for svc in order-service product-service payment-service; do
            sed -n '/CREATE TABLE outbox_events/,/^) ENGINE/p' \
                order-service/src/main/resources/db/migration/V1__init_order.sql \
                > "$TMP/$svc/src/main/resources/db/migration/V1__outbox_base.sql"
            cp order-service/src/main/resources/db/migration/V*__outbox_replay_columns.sql \
               "$TMP/$svc/src/main/resources/db/migration/V3__outbox_replay_columns.sql"
            cp order-service/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java \
               "$TMP/$svc/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java"
        done
        # payment 는 BACKFILL 없는 판본 (2집합 계약)
        cp payment-service/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java \
           "$TMP/payment-service/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java"
        cp notification-service/src/main/resources/db/migration/V*__outbox_events.sql \
           "$TMP/notification-service/src/main/resources/db/migration/V4__outbox_events.sql"
    }

    # self-test 1: 정상 사본 4개 → 통과
    seed_fixture
    if ! python3 "$LINT_PY" "$TMP" >/dev/null; then
        echo "self-test 1 실패: 동일 사본인데 위반으로 판정"; exit 1
    fi

    # self-test 2: 한 서비스 컬럼 변조 → 검출되어야 한다
    sed -i.bak 's/note                  VARCHAR(1000) NULL/note                  VARCHAR(500) NULL/' \
        "$TMP/product-service/src/main/resources/db/migration/V1__dead_letter_records.sql"
    if python3 "$LINT_PY" "$TMP" >/dev/null 2>&1; then
        echo "self-test 2 실패: 스키마 차이를 검출하지 못했다"; exit 1
    fi

    # self-test 3: NOT NULL 제거 → 검출되어야 한다
    seed_fixture
    sed -i.bak 's/failed_consumer_group VARCHAR(120) NOT NULL/failed_consumer_group VARCHAR(120) NULL    /' \
        "$TMP/order-service/src/main/resources/db/migration/V1__dead_letter_records.sql"
    for svc in product-service payment-service notification-service; do
        sed -i.bak 's/failed_consumer_group VARCHAR(120) NOT NULL/failed_consumer_group VARCHAR(120) NULL    /' \
            "$TMP/$svc/src/main/resources/db/migration/V1__dead_letter_records.sql"
    done
    if python3 "$LINT_PY" "$TMP" >/dev/null 2>&1; then
        echo "self-test 3 실패: 식별자 컬럼 nullable 을 검출하지 못했다"; exit 1
    fi

    # self-test 4: 마이그레이션 누락 → 검출되어야 한다
    #   **정상 fixture 에서 시작하고 위반 코드를 직접 단언한다** — self-test 3 의 변조를 이어받은 채
    #   "비정상 종료" 만 확인하면, 001 검사를 지워도 남아 있는 006 위반 때문에 계속 통과한다(false-green).
    seed_fixture
    rm "$TMP/notification-service/src/main/resources/db/migration/V1__dead_letter_records.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-001" <<<"$OUT"; then
        echo "self-test 4 실패: 마이그레이션 누락(001)을 검출하지 못했다"; exit 1
    fi

    # self-test 5: replay 축 컬럼을 NOT NULL 로 → 검출되어야 한다
    #   (order 가 아니라 **payment** 를 변조한다 — 기준 1벌만 검사하면 새 나가는 경로를 막기 위해서다)
    seed_fixture
    sed -i.bak 's/ADD COLUMN publication_status       VARCHAR(20)   NULL,/ADD COLUMN publication_status       VARCHAR(20)   NOT NULL,/' \
        "$TMP/payment-service/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-011" <<<"$OUT"; then
        echo "self-test 5 실패: replay 축 컬럼 NOT NULL 을 검출하지 못했다"; exit 1
    fi

    # self-test 6: java 를 한 벌만 수정 → 검출되어야 한다
    seed_fixture
    echo "// drift" >> "$TMP/product-service/src/main/java/com/peekcart/global/deadletter/DeadLetterStatus.java"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-013" <<<"$OUT"; then
        echo "self-test 6 실패: java 복제 drift 를 검출하지 못했다"; exit 1
    fi

    # self-test 7: replay 축 마이그레이션 누락 → 검출되어야 한다
    seed_fixture
    rm "$TMP/notification-service/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-007" <<<"$OUT"; then
        echo "self-test 7 실패: replay 축 마이그레이션 누락을 검출하지 못했다"; exit 1
    fi

    # self-test 8: **주석만** 갈라진 경우 → 009 로 검출되어야 한다.
    #   타입/길이 변경으로 검사하면 normalize 해시로도 잡히므로 "원문 바이트 대조" 로 바꾼 것 자체를
    #   고정하지 못한다. 주석은 normalize 가 지우므로, 이 케이스가 red 여야 byte 해시가 실제로 동작한다.
    seed_fixture
    printf '\n-- drift comment\n' >> "$TMP/product-service/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-009" <<<"$OUT"; then
        echo "self-test 8 실패: 주석만 다른 사본(normalize 로는 동일)을 검출하지 못했다"; exit 1
    fi

    # self-test 8b: nullable 을 유지한 **타입/길이** drift → 009 로 검출 (011 은 nullable 만 본다)
    seed_fixture
    sed -i.bak 's/ADD COLUMN replay_policy            VARCHAR(120)  NULL,/ADD COLUMN replay_policy            VARCHAR(200)  NULL,/' \
        "$TMP/product-service/src/main/resources/db/migration/V2__dead_letter_replay_axis.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-009" <<<"$OUT"; then
        echo "self-test 8b 실패: replay 축 SQL 타입/길이 drift 를 검출하지 못했다"; exit 1
    fi

    # self-test 9: java 파일 **삭제** → 012 로 검출 (013 은 존재하는 파일끼리만 비교한다)
    seed_fixture
    rm "$TMP/payment-service/src/main/java/com/peekcart/global/deadletter/DeadLetterTransitionService.java"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-012" <<<"$OUT"; then
        echo "self-test 9 실패: java 파일 누락을 검출하지 못했다"; exit 1
    fi

    # self-test 9b: **신규 복제 파일(reconciler)이 목록에 실제로 들어있는지** — 파일을 4벌 만들어 놓고
    #   목록에 더하는 것을 잊으면 그 파일만 무방비가 된다. drift 를 심어 013 이 뜨는지로 확인한다.
    seed_fixture
    echo "// drift" >> "$TMP/notification-service/src/main/java/com/peekcart/global/deadletter/DeadLetterPublicationReconciler.java"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-013" <<<"$OUT"; then
        echo "self-test 9b 실패: reconciler 복제 drift 를 검출하지 못했다 (java_files 목록 누락?)"; exit 1
    fi

    # self-test 9c: **목록↔디렉토리 정합** — 새 복제 파일을 만들고 목록에 안 더하면 014 로 검출된다.
    #   self-test 9b 는 "내가 기억한 그 파일" 만 보므로, 다음 신규 파일에는 아무 도움이 안 된다.
    seed_fixture
    for svc in order-service product-service payment-service notification-service; do
        cp "$TMP/order-service/src/main/java/com/peekcart/global/deadletter/DeadLetterStatus.java" \
           "$TMP/$svc/src/main/java/com/peekcart/global/deadletter/DeadLetterBrandNew.java"
    done
    # 대조군: 서비스마다 다른 파일은 걸리지 않아야 한다(정당한 per-service 파일까지 잡으면 lint 가 못 쓰게 된다).
    echo "// per-service" >> "$TMP/product-service/src/main/java/com/peekcart/global/deadletter/DeadLetterDiverges.java"
    for svc in order-service payment-service notification-service; do
        echo "// other" > "$TMP/$svc/src/main/java/com/peekcart/global/deadletter/DeadLetterDiverges.java"
    done
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-014.*DeadLetterBrandNew" <<<"$OUT"; then
        echo "self-test 9c 실패: java_files 목록에 없는 신규 복제 파일을 검출하지 못했다"; exit 1
    fi
    if grep -q "DLQ-PARITY-014.*DeadLetterDiverges" <<<"$OUT"; then
        echo "self-test 9c 실패: 서비스마다 다른 파일을 복제본으로 오탐했다"; exit 1
    fi

    # --- outbox 축 self-test (④-c-2b-2 P9-b · 계획 V-31) ---

    # self-test 10: notification 의 outbox 컬럼 1개 제거 → 002 로 검출
    #   3서비스는 CREATE+ALTER, notification 은 CREATE 단독이라 **경로가 다른 두 벌의 최종 스키마 비교**가
    #   실제로 도는지 여기서 확인한다.
    seed_fixture
    sed -i.bak '/trace_id          VARCHAR(64)  NULL,/d' \
        "$TMP/notification-service/src/main/resources/db/migration/V4__outbox_events.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "OUTBOX-PARITY-002" <<<"$OUT"; then
        echo "self-test 10 실패: outbox 최종 스키마 차이를 검출하지 못했다"; exit 1
    fi

    # self-test 11: replay 축 컬럼에 DEFAULT 부여 → 005 로 검출 (ADR-0020 D3)
    seed_fixture
    sed -i.bak "s/ADD COLUMN record_kind             VARCHAR(10)  NULL,/ADD COLUMN record_kind             VARCHAR(10)  NULL DEFAULT 'DOMAIN',/" \
        "$TMP/product-service/src/main/resources/db/migration/V3__outbox_replay_columns.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "OUTBOX-PARITY-005" <<<"$OUT"; then
        echo "self-test 11 실패: replay 축 컬럼의 DEFAULT 를 검출하지 못했다"; exit 1
    fi

    # self-test 12: replay 축 컬럼을 NOT NULL 로 → 004 로 검출
    seed_fixture
    sed -i.bak 's/ADD COLUMN destination_topic       VARCHAR(120) NULL,/ADD COLUMN destination_topic       VARCHAR(120) NOT NULL,/' \
        "$TMP/payment-service/src/main/resources/db/migration/V3__outbox_replay_columns.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "OUTBOX-PARITY-004" <<<"$OUT"; then
        echo "self-test 12 실패: replay 축 컬럼 NOT NULL 을 검출하지 못했다"; exit 1
    fi

    # self-test 13: global/outbox java 한 벌만 수정 → 007 로 검출
    seed_fixture
    echo "// drift" >> "$TMP/product-service/src/main/java/com/peekcart/global/outbox/OutboxEvent.java"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "OUTBOX-PARITY-007" <<<"$OUT"; then
        echo "self-test 13 실패: global/outbox java 복제 drift 를 검출하지 못했다"; exit 1
    fi

    # self-test 14: **OutboxEventStatus 를 4벌 동일하게 만들면 red 여야 한다** (2집합 계약).
    #   이 케이스가 green 이면 "전부 동일" 검사로 퇴화한 것이고, 그러면 BACKFILL 이 payment/notification 에
    #   새어도 아무 것도 실패하지 않는다.
    seed_fixture
    cp order-service/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java \
       "$TMP/notification-service/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java"
    cp order-service/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java \
       "$TMP/payment-service/src/main/java/com/peekcart/global/outbox/OutboxEventStatus.java"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "OUTBOX-PARITY-009" <<<"$OUT"; then
        echo "self-test 14 실패: OutboxEventStatus 2집합 계약 붕괴를 검출하지 못했다"; exit 1
    fi

    # self-test 15: 2집합을 **지킨** 상태는 통과해야 한다 — 항상 red 인 검사는 검사가 아니다.
    seed_fixture
    if ! python3 "$LINT_PY" "$TMP" >/dev/null; then
        echo "self-test 15 실패: 2집합 계약을 지킨 정상 fixture 를 위반으로 판정"; exit 1
    fi

    # --- ④-c-2b-3a P14(f-2): 최종 스키마 축 (2R #6) ---
    # 이 4종의 요점은 **replay 축 glob 밖의 파일**을 건드린다는 것이다. 이전 구조라면 전부 green 이었다.

    # self-test 16: 한 서비스에서만 digest 컬럼 제거 → 016(스키마 차이) + 017(앵커 부재)
    seed_fixture
    rm "$TMP/payment-service/src/main/resources/db/migration/V5__dead_letter_replay_payload_digest.sql"
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-017" <<<"$OUT"; then
        echo "self-test 16 실패: 상관 앵커 컬럼 부재를 검출하지 못했다 (glob 밖 파일이라 놓쳤나?)"; exit 1
    fi
    if ! grep -q "DLQ-PARITY-016" <<<"$OUT"; then
        echo "self-test 16 실패: 최종 스키마 차이를 검출하지 못했다"; exit 1
    fi

    # self-test 17~19 는 **4서비스를 함께** 변이시킨다. 그래야 016(서비스 간 차이)이 아니라
    # 해당 검사 자신이 red 를 만든 것이 증명된다 — 016 에 업혀 가는 검사는 검사가 아니다.
    # self-test 17: digest 를 VARCHAR(32) 로 → 018
    seed_fixture
    for svc in order-service product-service payment-service notification-service; do
        sed -i.bak 's/last_replay_payload_digest VARCHAR(64) NULL/last_replay_payload_digest VARCHAR(32) NULL/' \
            "$TMP/$svc/src/main/resources/db/migration/V5__dead_letter_replay_payload_digest.sql"
    done
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-018" <<<"$OUT"; then
        echo "self-test 17 실패: digest 타입 축소를 검출하지 못했다 (잘린 digest 는 다른 payload 를 같다고 말한다)"; exit 1
    fi
    if grep -q "DLQ-PARITY-016" <<<"$OUT"; then
        echo "self-test 17 실패: 4서비스 동시 변이인데 스키마 차이로 잡혔다 (018 이 016 에 업혀 있다)"; exit 1
    fi

    # self-test 18: digest 를 NOT NULL 로 → 019
    seed_fixture
    for svc in order-service product-service payment-service notification-service; do
        sed -i.bak 's/last_replay_payload_digest VARCHAR(64) NULL/last_replay_payload_digest VARCHAR(64) NOT NULL/' \
            "$TMP/$svc/src/main/resources/db/migration/V5__dead_letter_replay_payload_digest.sql"
    done
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-019" <<<"$OUT"; then
        echo "self-test 18 실패: 앵커 컬럼 NOT NULL 을 검출하지 못했다"; exit 1
    fi

    # self-test 19: digest 에 DEFAULT 부여 → 020
    seed_fixture
    for svc in order-service product-service payment-service notification-service; do
        sed -i.bak "s/last_replay_payload_digest VARCHAR(64) NULL/last_replay_payload_digest VARCHAR(64) NULL DEFAULT ''/" \
            "$TMP/$svc/src/main/resources/db/migration/V5__dead_letter_replay_payload_digest.sql"
    done
    OUT="$(python3 "$LINT_PY" "$TMP" 2>&1 || true)"
    if ! grep -q "DLQ-PARITY-020" <<<"$OUT"; then
        echo "self-test 19 실패: 앵커 컬럼 DEFAULT 를 검출하지 못했다 (기본값은 앵커 미기록을 삼킨다)"; exit 1
    fi

    # self-test 20: **음성 대조군** — digest 를 지키는 정상 fixture 는 통과해야 한다.
    #   17~19 가 항상 red 를 내는 검사가 아님을 고정한다.
    seed_fixture
    if ! python3 "$LINT_PY" "$TMP" >/dev/null; then
        echo "self-test 20 실패: 정상 digest fixture 를 위반으로 판정"; exit 1
    fi

    echo "dead-letter-schema-parity-lint self-test 23종 통과"
    exit 0
fi

python3 "$LINT_PY" "$(pwd)"
