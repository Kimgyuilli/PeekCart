# ADR-0036: 장바구니의 로컬 상품 정보 조합

- **Status**: Accepted
- **Date**: 2026-10-17
- **Deciders**: 사용자 승인 범위에 따른 root 구현 결정
- **관련 Phase**: Phase 5

## Context
장바구니는 productId와 수량만 반환하고 Order의 product_price_cache는 단가만 저장한다.
product.updated에는 name/status/price/version이 이미 있다. Product 동기 조회는 ADR-0012 CQRS 경계를 깨므로 사용하지 않는다.

## Decision
기존 로컬 캐시에 nullable product_name/product_status를 추가하고 동일 이벤트 version으로 가격과 원자적으로 갱신한다. 기존보다 큰 version만 반영하고 동일 version/역전 이벤트는 계속 무시한다. processed_events 멱등 경계도 유지한다.

장바구니 GET 및 추가/수정 응답에 productName/unitPrice/productStatus/available을 추가한다.
기존 id/productId/quantity는 그대로 유지한다. available은 알려진 ACTIVE이면 true,
INACTIVE/SOLD_OUT이면 false, 정보 미수신/알 수 없는 status이면 null이다. 이는 이벤트 시점
상품 상태이며 실시간 재고·주문 성공 보장이 아니다.

기존 장바구니 item은 정보 미수신에도 유지하며 없는 가격은 null이다. 가짜 상품명이나
0가격을 만들거나, 전체 조회를 실패시키거나, 자동으로 item을 삭제하지 않는다.
가격만 있는 기존 seed/마이그레이션 행은 가격을 표시하되 metadata와 available은 null이다.
다음 더 높은 version 이벤트가 정보를 채운다. 동일 version replay로 보정하지 않는다.
장바구니 추가 ORD-009와 주문 생성 ORD-007 정책은 바꾸지 않는다.

상품 ID 집합을 한 번에 조회한다. metadata를 cart_items에 복제하지 않는다.
DB 마이그레이션은 nullable additive라 구 앱이 계속 읽고 쓸 수 있다. 확장 컬럼을 먼저
배포한 뒤 신 앱을 배포하고, 롤백 시 앱만 되돌리고 컬럼은 유지한다. 구 앱이 새 version의
가격만 갱신하면 metadata가 오래 남을 수 있으므로 혼재 배포 동안 표시가 더 지연될 수 있다.

## Alternatives Considered
- Product 동기 조회: 서비스 간 동기 의존과 장애 전파 때문에 선택하지 않는다.
- 미수신 item 제거/전체 오류: 사용자의 장바구니를 잃거나 부분 가용성을 잃으므로 선택하지 않는다.
- 동일 version의 metadata만 보정: 기존 역전/replay 계약을 변경하므로 선택하지 않는다.

## Consequences
가격·상품 정보는 eventual consistency이며 null 처리가 필요하다. 상태 정보는 장바구니
표시용이고 주문의 최종 재고 검증은 기존 Saga에서 수행한다. 새 외부 의존이나 event schema 변경은 없다.
