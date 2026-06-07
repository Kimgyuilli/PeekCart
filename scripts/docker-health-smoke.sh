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
