# ADR-0037: 상품 목록 정렬과 캐시 조회의 DB 진입 경계

- Status: Accepted
- Date: 2026-10-17
- Deciders: Kimgyuilli (D-069 DB 보호 우선·초과 요청 503 승인), Codex (기존 관례에 따른 구현)

## Context

D-066의 목록 캐시 키는 정렬을 제외해 서로 다른 요청 결과가 섞인다. D-072의 조회 facade 트랜잭션은
캐시 hit에도 MySQL 세션 명령을 전송한다. D-069의 Redis fail-open은 모든 동시 요청을 DB로 보낸다.

## Decision

1. offset API를 유지한다. 기본 id ASC, 허용 정렬은 id/name/price의 ASC/DESC다. 명시 정렬에 id가
   없으면 마지막 id ASC를 붙여 동률 순서를 정한다. 중복·미지원 필드/옵션은 SYS-003 400이다.
   정규화된 정렬을 목록 캐시 키에 포함한다. 커서 전환은 하지 않는다.
2. ProductQueryService는 트랜잭션을 열지 않는다. cache advice는 order=-200으로 지정하며
   DB miss 본문은 기존 readOnly 트랜잭션 안에서 DTO로 완성한다. 직렬화 전에 지연 연관을 읽는다.
3. 상품 캐시 miss 본문 앞에 DB 진입 제한 advice(order=-100)를 둔다. cache hit은 제한을 통과하지
   않아도 된다. transaction advice(기본 LOWEST_PRECEDENCE)보다 먼저 permit을 얻고 finally로 반납한다.
   Redis 장애/정상 cache miss/캐시 비활성 모두 동일한 진입 상한을 적용한다.
4. 기본 동시 DB cache load는 1이다. 측정한 처리율 SLO가 없는 상태에서 DB 보호 우선이라는
   승인에 따라 직렬 실행을 보수적 기준으로 삼는다. `peekcart.cache.db-max-concurrency`로 양의 값만
   허용하며 Hikari maximumPoolSize보다 작아야 한다. 대기열은 두지 않고 초과는 PRD-005 503으로 거절한다.
   현재 Hikari 기본 풀 10개에 대해 1개만 cache miss에 허용하여 나머지 9개를 쓰기·메시지 소비·스케줄러와
   다른 조회가 공유하도록 남긴다. 각 사용자에게 9개를 보장하는 정책이 아니며 그 경로 전체의 상한은 별개다.
   검증은 실제 Hikari 최대/active/idle과 차단된 read 옆의 실제 UPDATE 성공을 기록한다.
   측정으로 상한 증액을 입증하기 전에는 최소 양의 동시성 1을 사용한다.
   인스턴스별 상한이며 여러 pod 전체의 전역 상한이나 DB 전체 부하 제한을 보장하지 않는다.

## Alternatives

- sync=true만 적용하면 같은 cache key에 한정되고 Redis 장애 시 여러 key가 동시에 DB에 진입한다.
- 풀 포화까지 기다리면 쓰기/메시지 소비도 커넥션 대기에 노출된다.
- Hikari 전역 auto-commit 정책 변경은 JDBC 경로 전체에 영향을 주므로 선택하지 않는다.

## Consequences and verification

읽기 초과 요청은 Redis가 정상이어도 cold miss가 겹치면 503일 수 있다. 호출자는 backoff 후 재시도한다.
상한을 높이려면 동시성 실험과 다른 DB 사용자 여유를 재검증한다. 로컬 결과를 GKE SLO로 일반화하지 않는다.
실제 Redis 장애·동시 호출에서 DB 진입 상한/503/회복을 검증하고, MySQL 서버측 general_log로 cache hit
명령 0과 miss 명령 존재를 비교한다. advisor 순서도 실제 proxy에서 확인한다.
