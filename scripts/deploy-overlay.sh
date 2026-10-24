#!/usr/bin/env bash
# deploy-overlay.sh — 앱 overlay 배포 래퍼 (④-c-2b-4b P24 · ADR-0022 §D3)
#
# preflight(drain 판정) → kafka 선행 적용·토픽 사전 생성(D-107) → kubectl apply -k <overlay> 순으로 감싼다.
#
# **왜 래퍼인가**: 이 레포에는 CD 가 없다 — `ci.yml` 에 deploy job 이 0건이고 배포는 운영자의 수동
#   `kubectl apply -k` 다. 그래서 판정을 붙일 자리가 배포 명령 자체밖에 없다.
#   in-cluster initContainer 게이트가 진짜 강제지만 4서비스 × DB 자격증명 + mysql 클라이언트 이미지 +
#   **정상 배포마다 드는 상시 비용** 때문에 채택하지 않았다(ADR-0022 Alternative G).
#
# **감싸는 범위는 앱 overlay 뿐이다**: monitoring/shared 매니페스트 적용은 서비스 이미지를 바꾸지 않아
#   롤백 위험과 무관하다. 그것들은 raw `kubectl apply -k` 를 그대로 쓴다.
#
# **강제력의 한계**: 운영자가 이 래퍼를 건너뛰고 `kubectl apply -k` 를 직접 치면 우회된다.
#   검증(계획 V-28)은 "래퍼가 exit≠0 이다" 만 확인하고 **"우회 불가" 를 주장하지 않는다**.
#   토픽 사전 생성도 같다 — raw apply 는 kafka 와 앱을 한 번에 올린다.
#
# 사용:
#   scripts/deploy-overlay.sh k8s/overlays/gke
#   scripts/deploy-overlay.sh k8s/overlays/minikube
#   DRAIN_PREFLIGHT_SKIP_REASON="..." scripts/deploy-overlay.sh <overlay>   # 아래 참고
#   TOPIC_PRECREATE_SKIP_REASON="..." scripts/deploy-overlay.sh <overlay>   # kafka 가 없는 overlay 등
#
# Exit: 0 배포 성공 · 1 preflight 위반, 토픽 생성 실패·파티션 불일치, 배포 실패 · 2 사용법/전제 오류
#       (토픽 정본 emit 실패, overlay 렌더 실패, 렌더에 kafka 리소스 없음 포함)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

TAG="[DEPLOY-OVERLAY]"
OVERLAY="${1:-}"

if [[ -z "$OVERLAY" ]]; then
    echo "사용법: $0 <overlay 경로> (예: k8s/overlays/gke)" >&2
    exit 2
fi
if [[ ! -d "$OVERLAY" ]]; then
    echo "$TAG overlay 디렉토리가 없다: $OVERLAY" >&2
    exit 2
fi

# **skip 은 사유를 강제하고 로그에 남긴다.** 조용히 끌 수 있는 게이트는 결국 꺼진 채로 남는다.
# 이것은 강제가 아니라 **기록**이다 — 어차피 raw apply 로 우회 가능하므로, 우회를 어렵게 만드는 대신
# 우회했다는 사실이 남게 한다.
if [[ -n "${DRAIN_PREFLIGHT_SKIP_REASON:-}" ]]; then
    echo "$TAG !! drain preflight 를 건너뛴다 — 사유: ${DRAIN_PREFLIGHT_SKIP_REASON}"
    echo "$TAG !! replay 행이 남은 상태로 구 이미지를 배포하면 발행 경로가 손상된다(ADR-0022 §D2)"
else
    echo "$TAG drain preflight 실행 — 통과해야 배포한다"
    if ! bash scripts/replay-drain-preflight.sh; then
        echo "$TAG preflight 가 막았다. 배포하지 않는다" >&2
        exit 1
    fi
fi

# ── kafka 선행 적용과 토픽 사전 생성 (D-107) ─────────────────────────────────
#
# **왜 앱보다 먼저인가**: 브로커는 토픽 자동 생성을 켠 채로 둔다(`auto.create.topics.enable` 기본 true,
#   `apache/kafka:3.8.1` 의 `num.partitions=1`). 빈 브로커에 앱을 함께 올리면 ① 소유 서비스보다 먼저 뜬
#   컨슈머가 토픽을 1파티션·기본 config 로 만들고 ② 소유 서비스 KafkaAdmin 이 선언 파티션으로 늘려도 이미
#   구독 중인 컨슈머는 metadata 갱신(기본 5분)까지 새 파티션을 받지 않으며 ③ 생성 경합을 겪은 토픽은 기동 중
#   적용에서 config 를 받지 못한다. E2E(`saga-e2e-smoke.sh`)·approval-recovery lab 과 같은 정본(`--emit-topics`)과
#   같은 방식(`--create --if-not-exists` 뒤 describe 대조)을 쓴다. 브로커 설정은 바꾸지 않는다.
#
# **kafka 만 먼저 올리는 법**: overlay 렌더에서 Namespace 와 kafka 3종(PVC·Deployment·Service)을 골라 적용한다.
#   base 를 따로 적용하지 않는 이유는 overlay patch(gke 의 PVC storageClassName)가 빠진 PVC 가 먼저 생기기
#   때문이다(PVC spec 은 생성 뒤 바꿀 수 없다). Service 를 함께 올리는 이유는 브로커가 `kafka:29092` 를 광고해
#   pod 안의 CLI 도 그 주소로 다시 접속하기 때문이다. readiness 는 29092 TCP 이고 Kafka 3.8.1 은 브로커가
#   unfence 된 뒤에야 이 포트를 연다. 그래서 `rollout status` 가 E2E 의 broker healthcheck 자리를 맡는다.
#   이미 떠 있는 스택이면 kafka 적용은 변화가 없고 생성은 `--if-not-exists` 로 건너뛴다. 대조는 매번 한다.
#
# **여기서 하지 않는 것**: config 7키는 넣지 않는다. 소유 서비스 KafkaAdmin 이 기동 중 적용과 기동 뒤 재적용
#   (KafkaTopicReconciler)에서 선언과 다른 키를 고친다. 측정 run 직전에는 `kafka-topic-state-guard.py` 가
#   파티션·config 실효값·컨슈머 할당을 다시 본다. 이 단계는 배포 시점 검사라 그 뒤의 변경은 막지 않는다.
#
# **건너뛰기**: drain preflight 와 같다. 사유를 강제하고 크게 남긴다.
NS="${NAMESPACE:-peekcart}"
if [[ -n "${TOPIC_PRECREATE_SKIP_REASON:-}" ]]; then
    echo "$TAG !! 토픽 사전 생성을 건너뛴다 — 사유: ${TOPIC_PRECREATE_SKIP_REASON}"
    echo "$TAG !! 빈 브로커라면 먼저 뜬 컨슈머가 토픽을 1파티션·기본 config 로 만든다(D-107)"
else
    if ! command -v python3 >/dev/null 2>&1; then
        echo "$TAG python3 가 필요하다(overlay 렌더에서 kafka 리소스를 고른다)" >&2
        exit 2
    fi
    WORK="$(mktemp -d)"
    trap 'rm -rf "$WORK"' EXIT

    if ! bash scripts/kafka-subscription-contract-lint.sh --emit-topics > "$WORK/topics.tsv"; then
        echo "$TAG 토픽 정본(--emit-topics)을 만들지 못했다. 배포하지 않는다" >&2
        exit 2
    fi
    if ! awk -F'\t' 'NF != 2 || $2 !~ /^[1-9][0-9]*$/ { bad = 1 } END { exit (bad || NR < 10) }' "$WORK/topics.tsv"; then
        echo "$TAG 토픽 정본 형식이 다르다(토픽<TAB>파티션, 10줄 이상). 배포하지 않는다" >&2
        exit 2
    fi
    TOPIC_COUNT="$(wc -l < "$WORK/topics.tsv" | tr -d ' ')"

    if ! kubectl kustomize "$OVERLAY" > "$WORK/rendered.yml"; then
        echo "$TAG overlay 를 렌더하지 못했다: $OVERLAY" >&2
        exit 2
    fi
    # kustomize 출력은 문서마다 최상위 `kind:` 와 2칸 들여쓴 `metadata.name` 을 갖는다. Namespace 를 맨 앞에 둔다.
    if ! python3 - "$WORK/rendered.yml" > "$WORK/kafka-first.yml" <<'PY'
import re
import sys

KAFKA = {("PersistentVolumeClaim", "kafka-pvc"), ("Deployment", "kafka"), ("Service", "kafka")}
picked, found = [], set()
for doc in re.split(r"(?m)^---\n", open(sys.argv[1], encoding="utf-8").read()):
    kind = re.search(r"(?m)^kind: (\S+)$", doc)
    name = re.search(r"(?m)^metadata:\n(?:  .*\n)*?  name: (\S+)$", doc)
    if kind is None or name is None:
        continue
    key = (kind.group(1), name.group(1))
    if key[0] == "Namespace" or key in KAFKA:
        picked.append((key[0] != "Namespace", doc.rstrip("\n") + "\n"))
        found.add(key)
missing = sorted("%s/%s" % key for key in KAFKA - found)
if missing:
    print("렌더에 kafka 리소스가 없다: " + ", ".join(missing), file=sys.stderr)
    sys.exit(1)
sys.stdout.write("---\n".join(doc for _, doc in sorted(picked, key=lambda item: item[0])))
PY
    then
        echo "$TAG kafka 를 먼저 올릴 수 없다. kafka 가 없는 overlay 면 TOPIC_PRECREATE_SKIP_REASON 에 사유를 남긴다" >&2
        exit 2
    fi

    echo "$TAG kafka 선행 적용 (Namespace, kafka PVC·Deployment·Service)"
    if ! kubectl apply -f "$WORK/kafka-first.yml"; then
        echo "$TAG kafka 선행 적용이 실패했다. 앱을 적용하지 않는다" >&2
        exit 1
    fi
    if ! kubectl -n "$NS" rollout status deploy/kafka --timeout=300s; then
        echo "$TAG kafka 롤아웃이 300초 안에 끝나지 않았다. 앱을 적용하지 않는다" >&2
        exit 1
    fi

    # 목록을 stdin 으로 넘겨 exec 1회 안에서 토픽마다 CLI 를 돈다. 첫 실패에서 멈춘다.
    echo "$TAG 선언 토픽 ${TOPIC_COUNT}개 생성 (--create --if-not-exists)"
    if ! kubectl -n "$NS" exec -i deploy/kafka -- sh -c '
        while read -r topic parts; do
            /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --create --if-not-exists \
                --topic "$topic" --partitions "$parts" --replication-factor 1 >/dev/null || exit 1
        done' < "$WORK/topics.tsv"; then
        echo "$TAG 토픽 생성이 실패했다. 앱을 적용하지 않는다" >&2
        exit 1
    fi

    # 생성 명령의 성공과 별개로 브로커 상태를 대조한다. 요약 줄만 센다(파티션별 줄은 탭으로 시작한다).
    if ! kubectl -n "$NS" exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:29092 --describe > "$WORK/described.txt"; then
        echo "$TAG 토픽 describe 가 실패했다. 앱을 적용하지 않는다" >&2
        exit 1
    fi
    if ! awk -F'\t' '
        NR == FNR { want[$1] = $2; order[++n] = $1; next }
        /^Topic: / {
            for (i = 2; i <= NF; i++) if ($i ~ /^PartitionCount: /) have[substr($1, 8)] = substr($i, 17)
        }
        END {
            for (k = 1; k <= n; k++) {
                t = order[k]
                if (!(t in have)) { printf "  - %s: 없음 (선언 %s)\n", t, want[t]; bad++ }
                else if (have[t] != want[t]) { printf "  - %s: %s (선언 %s)\n", t, have[t], want[t]; bad++ }
            }
            exit (bad > 0)
        }' "$WORK/topics.tsv" "$WORK/described.txt" > "$WORK/mismatch.txt"; then
        echo "$TAG 토픽 파티션 수가 선언과 다르다. 앱을 적용하지 않는다" >&2
        cat "$WORK/mismatch.txt" >&2
        echo "$TAG 선언보다 적으면 kafka-topics.sh --alter --partitions 로 늘린 뒤 다시 실행한다" >&2
        exit 1
    fi
    echo "$TAG 토픽 ${TOPIC_COUNT}개 파티션 수 일치"
fi

echo "$TAG kubectl apply -k $OVERLAY"
kubectl apply -k "$OVERLAY"

# ── 배포 후 인증 왕복 게이트 (D-022) ─────────────────────────────────────────
#
# **왜 배포 뒤인가**: 키쌍이 어긋나도 apply 는 전부 성공하고 파드는 Ready 가 되며 로그인도 200 이다.
#   깨진 것은 **인증된 요청**뿐이라 배포 시점에는 아무 신호가 없다. D-002 측정 세션이 그렇게 통과했고,
#   원인(ConfigMap 공개키 ↔ Secret Manager 개인키 불일치)을 찾는 데 40분이 들었다.
#
# **강제력**: `GW_URL` 이 있으면 돌리고, 없으면 **건너뛴 사실을 크게 남긴다**. 위 drain preflight 와
#   같은 철학이다 — 우회를 막을 수 없으니 우회가 보이게 한다. 게이트가 실패하면 배포는 이미 적용된
#   뒤이므로 이 스크립트는 **되돌리지 않고 exit 1 로 알린다**(롤백 판단은 운영자 몫).
if [[ -n "${GW_URL:-}" ]]; then
    echo "$TAG 인증 왕복 게이트 실행 (GW_URL=$GW_URL)"
    echo "$TAG   롤아웃 완료 대기…"
    kubectl -n "${NAMESPACE:-peekcart}" rollout status deploy/gateway --timeout="${GATE_ROLLOUT_TIMEOUT:-300s}" || true
    if ! GW_URL="$GW_URL" bash scripts/auth-roundtrip-gate.sh; then
        echo "$TAG !! 인증 왕복이 깨졌다 — 배포는 적용됐다. 위 진단을 보고 롤백/수정을 판단하라" >&2
        exit 1
    fi
else
    echo "$TAG !! 인증 왕복 게이트를 건너뛴다 — GW_URL 미설정"
    echo "$TAG !! 키쌍이 어긋나면 배포·Ready·로그인이 전부 정상인데 인증 요청만 401 이 된다(D-022)"
    echo "$TAG !! 수동 실행:  GW_URL=http://<gateway>:8080 bash scripts/auth-roundtrip-gate.sh"
fi
