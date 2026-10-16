# D-073 — D-002 기록 정정 근거 (2026-10-17)

기존 측정값과 ADR 결정은 보존한다. 이 문서는 새 측정이 아니라 원본 세션과 git 기록의 대조다.

## 1. CPU 표본은 400 VU 천장 표본이 아니다

원본: Claude 프로젝트 세션 `6b42b3e7-320a-4dfc-bf8e-74a6ed7902d7.jsonl`.
로컬 위치는 `~/.claude/projects/-Users-kimgyuill-dev-projects-PeakCart/`이다.
재현에 불필요한 계정·인프라 주소는 아래 발췌에서 제외했다.

| UTC 시각 / 원본 행 | 원본 실행과 결과 |
|---|---|
| 2026-09-15 15:47:17.981 / 1125 | cache OFF, product CPU limit 3000m, `VUS=50 DUR=60s`; list와 detail 순차 측정 |
| 15:50:56.787 / 1132 | detail `21548 358.454669/s` |
| 15:51:18.493 / 1136 | **별도** `EP=detail VUS=50 DUR=70s` 백그라운드 부하 실행. `kubectl top pod -n peekcart --no-headers`를 10초 간격으로 5회 호출 |
| 15:52:14.250 / 1137 | MySQL/product CPU: `356m/690m`, `356m/690m`, `207m/354m`, `207m/354m`, `207m/354m` |
| 15:54:51.913 / 1146 | 이후 별도 `VUS=50 100 200 400`, 각 `DUR=30s` 램프 실행 |
| 15:57:25.529 / 1147 | 400 VU detail `11724 378.148624/s` |

따라서 원 증적 §2차 병목의 “천장 상태에서 354~690m”는 잘못된 귀속이다.
CPU는 50 VU 런에서 수집했다. 358.5 rps도 같은 조건의 **직전 60초 런** 수치이며,
CPU를 수집한 70초 런의 처리량으로 동일시하지 않는다. `kubectl top`을 다섯 번
호출한 것이지 서로 독립인 다섯 순간 표본이라는 보장도 없다(반복된 값이 존재한다).
이 자료로 400 VU에서 CPU 포화나 throttling이 없었다고 결론낼 수 없다.

## 2. MySQL CPU는 관찰로 배제됐으나 인과 실험은 없었다

원 증적에는 `MySQL CPU | 아니다 | 207~356m` 행이 실제로 있다.
따라서 “D-002a가 배제한 CPU는 product-service만”이라는 후속 서술은 원문과 다르다.
당시 두 CPU를 관찰로 배제했지만, MySQL CPU limit은 500m 그대로였고 그 손잡이를
바꾸는 대조 실험은 하지 않았다. 특히 §1의 낮은 VU 관찰은 더 높은 VU 천장의
CPU 상한 원인을 배제하는 증거가 아니다. 이후 D-002bc와 D-026의 상한 변경 실험이
다룬 것은 이 **인과 검증의 공백**이다.

## 3. 하네스와 측정 PR 구분

로컬 main에 포함된 squash commit을 대조했다.

- `e3e9b3d`: `chore(d002bc): D-002 잔여 3축 측정 하네스 … (#119)`.
- `ff7e748`: `test(d002): D-002 종결 … (#120)`.
- 따라서 하네스는 [#119](https://github.com/Kimgyuilli/PeakCart/pull/119),
  GKE 측정 세션은 [#120](https://github.com/Kimgyuilli/PeakCart/pull/120)으로 인용한다.
- worker의 `gh pr view`는 API 연결 실패했으나 root가 원격 조회 후 다음 메타데이터를 전달했다(2026-10-17).
  #119: merge `e3e9b3d5078fc9120d121f9b217219852387f936`, 2026-09-16T12:48:59Z,
  제목 `chore(d002): 잔여 성능 축 측정 하네스 구축`.
  #120: merge `ff7e7482b64e4030e2a0c8e80fae42006d6dd4ca`, 2026-09-17T15:27:13Z,
  제목 `test(d002): 분산 락과 MySQL 병목 측정`. 로컬 squash SHA와 일치한다.

## 4. 락 역전은 모놀리스 도입 당시부터 있었다

`git show 7cadfa06ae7aff898bd212a13b2dd6b5cd2d0016:<path>`로 다음을 확인했다.

- `src/main/java/com/peekcart/order/application/OrderCommandService.java`: 클래스에
  `@Transactional`, `createOrder`가 `ProductPort.decreaseStockAndGetUnitPrice` 호출.
- `src/main/java/com/peekcart/product/infrastructure/adapter/ProductPortAdapter.java`:
  위 메서드가 `InventoryLockFacade.decreaseStock` 호출.
- `src/main/java/com/peekcart/product/application/InventoryLockFacade.java`: 내부 서비스
  호출을 마친 뒤 `finally`에서 unlock.
- `src/main/java/com/peekcart/product/application/InventoryService.java`: 기본 REQUIRED
  트랜잭션으로 바깥 주문 트랜잭션에 참여.

도입 시점부터 주문 트랜잭션이 락 획득·해제보다 바깥에 있었다. “이동 후 역전”은
역전이 새로 생겼다는 뜻으로 읽히므로 정정한다. 정확히는 **기존 역전이 Product 예약
consumer 경로에도 이어졌다**. L-007의 D-025 흡수 및 ADR-0025의 제거 결정은 바뀌지 않는다.

## 5. 블로그 원본 대조

root가 제공한 별도 블로그 저장소의 `src/content/blog/peekcart-28-ruled-out-bottleneck.mdx`
(로컬 `/Users/kimgyuill/dev/projects/blog/`)를 읽기 전용으로 확인했다(2026-10-17).
“로드맵 27편”으로 지목한 내용에 대응하는 실제 글은 28편이다.

- 114~120행은 CPU가 50 VU의 70초 런 표본이며 358.5 rps가 바로 앞의 런임을 이미 구분한다.
- 452행은 CPU 두 행이 모두 낮은 VU의 관찰이며 손잡이를 조작하지 않았다고 이미 명시한다.
- 314행은 모놀리스 당시부터 바깥 주문 트랜잭션 때문에 순서가 뒤집혔다고 이미 명시한다.
- 현재 글에 PR119/120 링크는 없어 잘못된 측정 PR 인용을 찾지 못했다.

외부 글은 이미 올바른 설명이라 수정하지 않았다. 저장소의 `docs/07-roadmap-portfolio.md`
자체에는 해당 CPU/27편 서술이 없다. 블로그 대조 조건은 충족했으며 새 외부 쓰기나 배포는 없다.
