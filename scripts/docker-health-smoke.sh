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
COMPOSE=(docker compose -p "$COMPOSE_PROJECT_NAME")

cleanup() {
    docker rm -f "$APP_CONTAINER" >/dev/null 2>&1 || true
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
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

# [PR3a] 공유 스키마 선행 마이그레이션 (Codex GP-2 #1).
# 비-order 서비스는 Flyway disabled + JPA ddl-auto:validate 라, 빈 DB 에 스키마가 없으면
# health 200 전에 schema validate 로 죽는다(런타임 마이그레이터는 order-service 단독).
# 앱 컨테이너 실행 전에 공유 스키마(common/.../db/migration V1~Vn)를 적용한다(멱등).
# ⚠️ 마이그레이션 정본 메모(전환기): root gradle `flywayMigrateShared`(flyway 11.7.2) 가 현재
#    깨져 있다(gradle flyway 플러그인이 mysql DB 플러그인 미해석 — "No Flyway database plugin found").
#    런타임 마이그레이션은 Spring Boot 관리 Flyway(order-service)라 별개 경로로 동작한다. smoke 는
#    공식 flyway 이미지(DB 플러그인 번들·호스트 JDK 무의존)를 정본으로 쓴다. flywayMigrateShared
#    수복은 후속 부채(plan §5/audit 에 기록). 이미지는 repo flyway 와 동일 11.7.2 를 digest 로 고정한다.
#    SMOKE_MIGRATE=0 으로 끌 수 있다(DB-per-service 분리 후 등).
if [[ "${SMOKE_MIGRATE:-1}" != "0" ]]; then
    echo "[D-012/L-015] applying shared schema (flyway image 11.7.2)"
    # allowPublicKeyRetrieval=true&useSSL=false — MySQL 8 caching_sha2_password 의 non-TLS
    # 비밀번호 교환(RSA public key) 처리. baselineOnMigrate — 빈 스키마/기존 객체에 무해.
    docker run --rm \
        --network "${COMPOSE_PROJECT_NAME}_default" \
        -v "${REPO_ROOT}/common/src/main/resources/db/migration:/flyway/sql:ro" \
        flyway/flyway:11.7.2@sha256:8ace7d9825bb3ad1d6e14ee27b3a830b638ac841ba424b99b2d92aa65a99d484 \
        -url="jdbc:mysql://mysql:3306/peekcart?allowPublicKeyRetrieval=true&useSSL=false&characterEncoding=UTF-8&serverTimezone=Asia/Seoul" \
        -user=peekcart -password=peekcart -connectRetries=15 -baselineOnMigrate=true migrate
fi

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

echo "[D-012/L-015] starting app container"
docker run -d \
    --name "$APP_CONTAINER" \
    --network "${COMPOSE_PROJECT_NAME}_default" \
    -p "${APP_PORT}:8080" \
    -e SPRING_PROFILES_ACTIVE=k8s \
    "$IMAGE" >/dev/null

echo "[D-012/L-015] waiting for /actuator/health"
for _ in {1..90}; do
    if [[ "$(curl -fsS -o /dev/null -w '%{http_code}' "http://localhost:${APP_PORT}/actuator/health" || true)" == "200" ]]; then
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
