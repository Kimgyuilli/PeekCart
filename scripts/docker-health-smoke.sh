#!/usr/bin/env bash
# docker-health-smoke.sh - D-012 / L-015 container runtime smoke gate
#
# Usage:
#   bash scripts/docker-health-smoke.sh <image-tag>
#
# The script starts the existing docker-compose infrastructure (MySQL, Redis,
# Kafka), runs the given app image on the same network with the k8s profile, and
# waits until /actuator/health returns HTTP 200.

set -euo pipefail

IMAGE="${1:-}"
if [[ -z "$IMAGE" ]]; then
    echo "usage: bash scripts/docker-health-smoke.sh <image-tag>" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if ! command -v docker >/dev/null 2>&1; then
    echo "[D-012/L-015] docker not found" >&2
    exit 2
fi

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-peekcart-smoke}"
APP_CONTAINER="${APP_CONTAINER:-peekcart-smoke-app}"
APP_PORT="${APP_PORT:-18080}"
# gateway 는 actuator 를 별도 관리 포트(컨테이너 8081)에 둔다 — 그 포트를 호스트로 노출해 health 를 확인한다.
# 다른 이미지는 8081 을 열지 않으므로 매핑만 되고 사용되지 않는다.
MGMT_PORT="${MGMT_PORT:-18081}"
COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME")

cleanup() {
    docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "${SMOKE_KEY_DIR:-}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cleanup

echo "[D-012/L-015] starting smoke infrastructure"
"${COMPOSE[@]}" up -d mysql redis kafka

echo "[D-012/L-015] waiting for MySQL"
for _ in {1..60}; do
    if "${COMPOSE[@]}" exec -T mysql env MYSQL_PWD=root mysqladmin ping -h 127.0.0.1 -uroot --silent >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
"${COMPOSE[@]}" exec -T mysql env MYSQL_PWD=root mysqladmin ping -h 127.0.0.1 -uroot --silent >/dev/null

# [구현 ② PR2] DB-per-service: 별도 flyway 선적용 스텝 제거.
# compose mysql init(scripts/mysql-init/01-init-databases.sql)이 5 스키마+5 계정을 첫 부팅에 생성하고,
# 각 <svc>:ci 앱이 부팅 시 자기 스키마에 자기 모듈 Flyway(flyway.enabled=true)를 적용한다.
# (전환기 flyway 이미지 선적용·flywayMigrateShared 우회는 소멸.)

echo "[D-012/L-015] waiting for Redis"
for _ in {1..30}; do
    if [[ "$("${COMPOSE[@]}" exec -T redis redis-cli ping 2>/dev/null)" == "PONG" ]]; then
        break
    fi
    sleep 1
done
[[ "$("${COMPOSE[@]}" exec -T redis redis-cli ping)" == "PONG" ]]

echo "[D-012/L-015] waiting for Kafka"
for _ in {1..60}; do
    if "${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
"${COMPOSE[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list >/dev/null

# RS256 서명 개인키 런타임 주입(구현 ③ PR1): User(발급) 컨테이너는 부팅 시 개인키를 요구(fail-fast).
# 개인키는 이미지에 포함하지 않으므로(ADR-0013 D2) smoke 는 throwaway 키를 런타임 생성·마운트해
# operator 주입을 시뮬레이션한다. 검증 공개키는 이미지 classpath 에 있어 발급 서비스가 아닌 컨테이너는 무시한다.
# (부팅 health 만 확인하므로 이 키가 공개키와 짝일 필요는 없다 — 토큰 왕복은 단위/통합 테스트가 검증.)
# 초기 cleanup 이후에 생성해야 삭제되지 않는다(SMOKE_KEY_DIR 은 trap cleanup 에서만 정리).
SMOKE_KEY_DIR="${SMOKE_KEY_DIR:-$(mktemp -d)}"
mkdir -p "$SMOKE_KEY_DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$SMOKE_KEY_DIR/jwt-private.pem" 2>/dev/null
# Gateway 내부 토큰 개인키(구현 ③ PR3d): gateway 컨테이너도 부팅 시 개인키를 요구한다(fail-fast).
# 사용자 토큰 서명키와 별개다 — 키 도메인을 섞지 않는다(ADR-0017 D3).
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
    -out "$SMOKE_KEY_DIR/gateway-internal-private.pem" 2>/dev/null
# 컨테이너 non-root 유저가 마운트를 traverse·read 할 수 있게 (mktemp -d 는 700) 디렉토리/파일 권한 개방
chmod 755 "$SMOKE_KEY_DIR"
chmod 644 "$SMOKE_KEY_DIR/jwt-private.pem" "$SMOKE_KEY_DIR/gateway-internal-private.pem"

echo "[D-012/L-015] starting app container"
# 자격증명 런타임 주입(GP-2 work #2/#3): k8s 프로파일은 SLACK_USER_WEBHOOK_URL/SLACK_OPS_WEBHOOK_URL(notification)·TOSS_*(payment)
# 를 no-default 로 강제(fail-fast). committed Secret 에는 placeholder 를 두지 않으므로(operator/external
# 주입), smoke 는 그 operator 주입을 dummy 값으로 시뮬레이션한다. 사용 안 하는 서비스는 무시한다.
# (이 값들은 smoke 런타임 전용 — 렌더 산출 manifest 에 새지 않는다.)
docker run -d \
    --name "$APP_CONTAINER" \
    --network "${COMPOSE_PROJECT_NAME}_default" \
    -p "${APP_PORT}:8080" \
    -p "${MGMT_PORT}:8081" \
    -e SPRING_PROFILES_ACTIVE=k8s \
    -e SLACK_USER_WEBHOOK_URL="http://127.0.0.1:9/user" \
    -e SLACK_OPS_WEBHOOK_URL="http://127.0.0.1:9/ops" \
    -e TOSS_SECRET_KEY="${SMOKE_TOSS_SECRET_KEY:-test_sk_smoke}" \
    -e TOSS_WEBHOOK_SECRET="${SMOKE_TOSS_WEBHOOK_SECRET:-test_webhook_smoke}" \
    -v "${SMOKE_KEY_DIR}:/smoke-keys:ro" \
    -e JWT_PRIVATE_KEY_LOCATION="file:/smoke-keys/jwt-private.pem" \
    -e GATEWAY_INTERNAL_PRIVATE_KEY_LOCATION="file:/smoke-keys/gateway-internal-private.pem" \
    "$IMAGE" >/dev/null

# health 경로/포트는 이미지 성격에 따라 다르다 (구현 ③ PR3a).
#   도메인 5서비스: 앱 포트(8080)의 루트 /actuator/health — smoke 망에 MySQL/Redis/Kafka 가 실제로
#                   떠 있어 의존성 연결까지 검증하는 것이 의미 있다.
#   gateway:        관리 포트(8081)의 /actuator/health/liveness.
#                   (a) gateway 는 actuator 를 외부 노출 포트에서 분리해 별도 관리 포트에 둔다
#                       (외부 진입점이라 /actuator/prometheus 가 인터넷에 노출되면 안 됨).
#                   (b) gateway 의 *readiness* 는 User JWKS 도달성에 달려 있는데 smoke 망에는
#                       user-service 가 없다(설계상). 루트 health 는 readinessState 를 집계하므로
#                       항상 503 이 되어, 프로세스는 정상인데 이미지가 불량으로 판정된다.
#                       smoke 가 확인할 것은 "이미지가 부팅되고 프로세스가 살아 있는가" = liveness.
#                   JWKS 적재 후 readiness 전이는 gateway 테스트 + PR3b k8s readinessProbe 가 검증한다.
HEALTH_PATH="${HEALTH_PATH:-/actuator/health}"
HEALTH_HOST_PORT="$APP_PORT"
if [[ "$IMAGE" == gateway:* || "$IMAGE" == *"peekcart-gateway"* ]]; then
    HEALTH_PATH="/actuator/health/liveness"
    HEALTH_HOST_PORT="$MGMT_PORT"
fi

echo "[D-012/L-015] waiting for :${HEALTH_HOST_PORT}${HEALTH_PATH}"
for _ in {1..90}; do
    if [[ "$(curl -fsS -o /dev/null -w '%{http_code}' "http://localhost:${HEALTH_HOST_PORT}${HEALTH_PATH}" || true)" == "200" ]]; then
        echo "[D-012/L-015] health smoke passed"
        exit 0
    fi
    if ! docker ps --format '{{.Names}}' | grep -qx "$APP_CONTAINER"; then
        echo "[D-012/L-015] app container exited before health became ready" >&2
        docker logs "$APP_CONTAINER" >&2 || true
        exit 1
    fi
    sleep 2
done

echo "[D-012/L-015] health smoke timed out" >&2
docker logs "$APP_CONTAINER" >&2 || true
exit 1
