#!/usr/bin/env bash
# d002bc-seed-products.sh — D-002b'/c 상품 시드 + 가격 캐시 전파 게이트
#
# **왜 SQL 이 아니라 API 인가**: 상품을 product 스키마에 직접 INSERT 하면 outbox 에
# product.updated 가 남지 않는다. 그러면 order-service 의 product_price_cache 가 비고,
# OrderCommandService:57 이 단가를 못 찾아 주문이 **전부 ORD-007 로 죽는다**.
# V1__init_order.sql:7 이 이 테이블을 "seed 제외 — cross-DB → product.updated replay" 로
# 못박아 둔 것이 같은 이유다. 그래서 admin API(ProductCommandService.create →
# publishProductUpdated)를 태우고, 캐시가 실제로 채워질 때까지 **기다린다**.
#
# 사용법:
#   GW=http://<gateway-internal-lb>:8080 bash loadtest/scripts/d002bc-seed-products.sh [--count 10]
#
# 전제: loadtest/sql/d002bc-user-seed.sql 적용 완료(loadadmin@peekcart.test / ADMIN).
set -euo pipefail

GW="${GW:?GW 미설정 — gateway Internal LB 주소를 넣어라 (예: http://10.178.0.40:8080)}"
COUNT=10
STOCK=100000

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count) COUNT="$2"; shift 2;;
    --stock) STOCK="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 1;;
  esac
done

echo "[0/4] 카테고리 확보"
# 카테고리는 admin API 가 없다(presentation 에 카테고리 컨트롤러 없음) — SQL 로 넣는다.
# products 와 달리 카테고리는 order 쪽 캐시와 무관하므로 outbox 를 태울 필요가 없다.
# 이 단계가 없으면 상품 생성이 전부 PRD-003(카테고리를 찾을 수 없습니다)으로 404 난다.
kubectl -n peekcart exec -i deploy/mysql -- \
  mysql -upeekcart_product -ppeekcart_product peekcart_product \
  -e "INSERT IGNORE INTO categories (id, name, parent_id) VALUES (1, 'loadtest', NULL); SELECT COUNT(*) AS categories FROM categories;" 2>&1 | grep -v "Using a password"

echo "[1/4] admin 로그인"
# 로그인 라우트는 **IP 키 10 req/s**(user-auth-preauth, burstCapacity 10) — 단발이라 여유롭다.
TOKEN=$(curl -sS -X POST "${GW}/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"loadadmin@peekcart.test","password":"LoadTest123!"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
[[ -n "$TOKEN" ]] || { echo "로그인 실패" >&2; exit 1; }

echo "[2/4] 상품 ${COUNT}건 생성 (admin API — product.updated 발행)"
# admin 라우트는 **사용자 키 40 req/s**. 1건씩 순차 생성이라 한도에 닿지 않는다.
CREATED=()
for i in $(seq 1 "$COUNT"); do
  body=$(printf '{"categoryId":1,"name":"d002bc-product-%d","description":"D-002b/c 측정용","price":%d,"stock":%d}' \
           "$i" $((1000 + i)) "$STOCK")
  id=$(curl -sS -X POST "${GW}/api/v1/admin/products" \
        -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
        -d "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
  CREATED+=("$id")
done
echo "  생성된 productId: ${CREATED[*]}"

echo "[3/4] order 의 product_price_cache 전파 대기 (최대 60s)"
# 이 게이트가 없으면 부하 시작 시점에 캐시가 덜 차 있어 ORD-007 이 섞이고,
# 그 실패율이 "주문 생성 경로가 느리다" 로 오독된다.
deadline=$(( $(date +%s) + 60 ))
while :; do
  n=$(kubectl -n peekcart exec -i deploy/mysql -- \
        mysql -N -B -upeekcart_order -ppeekcart_order peekcart_order \
        -e "SELECT COUNT(*) FROM product_price_cache WHERE product_id IN ($(IFS=,; echo "${CREATED[*]}"));" 2>/dev/null || echo 0)
  [[ "$n" == "$COUNT" ]] && { echo "  캐시 ${n}/${COUNT} — 전파 완료"; break; }
  (( $(date +%s) > deadline )) && { echo "  캐시 ${n}/${COUNT} — 60s 내 미전파. 부하 시작 금지" >&2; exit 1; }
  sleep 2
done

echo "[4/4] 결과"
printf 'PRODUCT_IDS=%s\n' "$(IFS=,; echo "${CREATED[*]}")"
echo "위 값을 k6 의 -e PRODUCT_IDS=... 로 넘겨라."
