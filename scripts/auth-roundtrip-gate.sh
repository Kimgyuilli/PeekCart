#!/usr/bin/env bash
# auth-roundtrip-gate.sh — 배포 직후 "인증이 실제로 통하는가" 를 막는 게이트 (D-022)
#
# 왜 이것이 따로 필요한가:
#   같은 검사가 `gke-security-smoke.sh` 안에 이미 있다(양성 대조군: 로그인 → 보호 경로 200).
#   그런데 그 스크립트는 `CLUSTER`·`DIRECT_ENDPOINTS` 를 요구하고 NetworkPolicy enforcement 를
#   함께 검사해서, NetworkPolicy 가 없는 측정/부분 overlay 에는 **적용할 수 없다**. 그래서 안 돌게 되고,
#   안 돌면 아무도 막지 않는다 — D-002 측정 세션이 정확히 그렇게 통과했다.
#
# 무엇을 막는가:
#   ConfigMap 공개키가 Secret Manager 개인키와 **다른 쌍**이면, 배포는 전부 성공하고 파드는 Ready 이며
#   로그인도 200 인데 **인증된 요청이 전부 401** 이다. kid 가 같으면 `unknown_kid` 로도 안 걸리고
#   `bad_signature` 로만 나타나 원인이 보이지 않는다. 실패 시 이 스크립트가 **어느 키 도메인이
#   어긋났는지 지목**한다 — 그 진단에 한 세션에서 40분이 들었다.
#
# 사용법:
#   GW_URL=http://<gateway>:8080 bash scripts/auth-roundtrip-gate.sh
# 선택:
#   NAMESPACE(기본 peekcart) · EMAIL/PASSWORD(기본 부하 시드 계정) · PROTECTED_PATH(기본 /api/v1/orders)
#
# Exit: 0 통과 / 1 인증 실패(원인 진단 출력) / 2 전제 미충족
set -uo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TAG="[AUTH-GATE]"
GW_URL="${GW_URL:?GW_URL 미설정 — gateway 진입점을 넣어라 (예: http://10.0.0.1:8080)}"
NAMESPACE="${NAMESPACE:-peekcart}"
EMAIL="${EMAIL:-loadadmin@peekcart.test}"
PASSWORD="${PASSWORD:-LoadTest123!}"
PROTECTED_PATH="${PROTECTED_PATH:-/api/v1/orders}"

command -v curl >/dev/null || { echo "$TAG curl 없음" >&2; exit 2; }

# ── (1) 로그인 ───────────────────────────────────────────────────────────────
login=$(curl -s --max-time 15 -X POST "$GW_URL/api/v1/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" 2>/dev/null)
token=$(sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' <<< "$login")

if [ -z "$token" ]; then
    echo "$TAG FATAL (1) 로그인 실패 — 토큰을 얻지 못했다. 계정 시드/발급 경로를 먼저 확인하라." >&2
    echo "$TAG   응답: $(head -c 200 <<< "$login")" >&2
    exit 1
fi
echo "$TAG (1) 로그인 OK"

# ── (2) 보호 경로 200 ── 이게 게이트다 ────────────────────────────────────────
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
       -H "Authorization: Bearer $token" "$GW_URL$PROTECTED_PATH" 2>/dev/null)

if [ "$code" = "200" ]; then
    echo "$TAG (2) 보호 경로 200 OK — 인증 왕복 성립"
    echo "$TAG PASS"
    exit 0
fi

echo "$TAG FATAL (2) 보호 경로 code=$code (기대 200) — 인증 왕복이 깨졌다" >&2

# ── (3) 원인 지목 ── 여기가 이 스크립트의 값이다 ──────────────────────────────
echo "$TAG --- 키쌍 진단 ---" >&2
command -v kubectl >/dev/null || { echo "$TAG kubectl 없음 — 진단 생략" >&2; exit 1; }

# 서명 개인키(CSI 마운트)에서 유도한 modulus 와 검증 공개키(ConfigMap)의 modulus 를 대조한다.
#   $1 라벨 · $2 파드 셀렉터 · $3 개인키 경로 · $4 ConfigMap 이름 · $5 ConfigMap 키
diagnose() {
    local label="$1" sel="$2" key="$3" cm="$4" cmkey="$5"
    local priv pub
    priv=$(kubectl -n "$NAMESPACE" exec "deploy/$sel" -- \
           sh -c "openssl rsa -in $key -pubout 2>/dev/null | openssl rsa -pubin -noout -modulus 2>/dev/null" 2>/dev/null)
    pub=$(kubectl -n "$NAMESPACE" get configmap "$cm" -o "jsonpath={.data.$cmkey}" 2>/dev/null \
          | openssl rsa -pubin -noout -modulus 2>/dev/null)
    if [ -z "$priv" ] || [ -z "$pub" ]; then
        echo "$TAG   [$label] 대조 불가 (priv=${priv:+있음}${priv:-없음} pub=${pub:+있음}${pub:-없음})" >&2
        return
    fi
    if [ "$priv" = "$pub" ]; then
        echo "$TAG   [$label] 일치" >&2
    else
        echo "$TAG   [$label] **불일치** — 서명 ${priv:0:24}… vs 검증 ${pub:0:24}…" >&2
        echo "$TAG     → ConfigMap '$cm' 의 공개키를 Secret Manager 개인키의 짝으로 교체하라." >&2
    fi
}

diagnose "사용자 토큰" user-service /etc/peekcart/user-keys/jwt-private.pem \
         user-jwt-public-keys 'peekcart-dev-2026\.pem'
diagnose "내부 토큰" gateway /etc/peekcart/gateway-keys/gateway-internal-private.pem \
         internal-token-keys 'peekcart-gateway-dev-2026\.pem'

echo "$TAG FAIL" >&2
exit 1
