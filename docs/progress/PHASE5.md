# Phase 5 진행 보고서 — 수요 기반

> Phase 5 작업 이력, 주요 결정 사항, 이슈 기록
> 작업 상태 추적은 `docs/TASKS.md §개발 부채 / 작업`

---

## 무료 범위 부채 병렬 실행 재개 (2026-10-21)

이번 사용자 승인은 아래 2026-10-17 기록의 D-064·D-065·D-078 전체 제외와 D-075 분석 보류를 대체한다. 세 항목의 무료 로컬 구현·검증을 진행하고 실제 PG·운영 표본·GKE 증거는 별도로 남긴다. D-075는 무료 병목 분석과 정책에 독립적인 수정이 승인됐으며, 목표 TPS·최대 backlog·초과 대응 정책은 아직 결정되지 않았다. D-084 영구404의 신뢰할 PG 증빙 원천·형식도 미확보다.

현재 범위·담당·검증 SHA·대기 조건은 [실행 계획](../plans/task-debt-20261021.md)에서 관리한다. 구현 담당자가 자기 worktree에서 작업하고, 구현하지 않은 리뷰 에이전트가 정확한 head를 검토한다. root가 순서를 승인한 뒤 담당자가 보호 규칙을 지켜 직렬 병합하며, 다음 병합 전 실제 main CI와 여섯 publish 결과를 확인한다. 운영 배포는 포함하지 않는다.

### D-085 릴리스 gate 의미 검증 완료

[PR185](https://github.com/Kimgyuilli/PeekCart/pull/185)의 head `d588914b30c89e02ecc70438cc210ced9f239f01`은 gate 조건의 실패 전파 의미와 step `continue-on-error` 우회를 검증한다. 독립 리뷰는 [대상 SHA와 실행 결과](https://github.com/Kimgyuilli/PeekCart/pull/185#issuecomment-6297405146)를 기록했으며, 55개 결함 변이·6개 정상 대조·6개 parser 검증과 원본이 놓치는 결함의 재현 근거를 대조했다. [PR CI39469123095](https://github.com/Kimgyuilli/PeekCart/actions/runs/39469123095)가 성공했다.

정상 병합 SHA는 `0e40b6acd2ffc3266fdca2caa1cb67131da8be34`이며, [main CI39471537474](https://github.com/Kimgyuilli/PeekCart/actions/runs/39471537474)는 E2E·gate 및 여섯 publish를 포함해 성공했다. 완료 행은 본문을 보존해 TASKS-done으로 이동했다. 이 완료 기록 자체의 PR 전달·검증은 실행 계획의 기록 브랜치에서 계속 진행한다.



### D-105 ship 재개 조회 실패 구분 완료

[PR184](https://github.com/Kimgyuilli/PeekCart/pull/184)의 최종 head `a5d483561648adac67610e9c886c4a636f4c3b50`은 원격·로컬 조회 실패와 정상적인 부재를 구분해 `unknown`/실패를 반환한다. 독립 [최종 delta 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/184#issuecomment-6297728123)는 변경 없는 작성 patch를 확인하고 18개 복구 검사와 gate 반례를 직접 재실행했다. 기존 62개 Bats는 변경 없는 코드의 이전 검증 근거이며 새로 재실행했다고 주장하지 않는다. [최신 PR CI39471735530](https://github.com/Kimgyuilli/PeekCart/actions/runs/39471735530)는 성공했다.

정상 병합 SHA `be535c57ced11acfcbe9a24a349ddb6ad2055d8a`의 [main CI39474188286](https://github.com/Kimgyuilli/PeekCart/actions/runs/39474188286)는 모든 검사와 여섯 publish가 성공했다. TASKS 완료 행 이동은 상태 외 본문 보존, 전체 ID 다중집합 동일성 및 유일성을 확인했다.

### D-097 실제 런타임 의존성 기준 중복 클래스 검사 완료

[PR187](https://github.com/Kimgyuilli/PeekCart/pull/187) 최종 head `cca3efbbc98ce6251afa7d13dfb9ece6a69dc119`은 gateway를 포함한 여섯 bootJar 앱의 실제 선택된 내부 런타임 프로젝트 산출물을 검사한다. runtimeOnly·전이 의존성을 포함하고 compileOnly·외부 jar·대상 외 패키지는 구분한다. [독립 초기 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/187#issuecomment-6297621464)는 실제 컴파일 8개 시나리오를 실행했고, [최종 delta 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/187#issuecomment-6298143655)는 작성 patch 동일성과 최신 main 통합을 확인한 뒤 관련 경량 검증을 실행했다. 8개 컴파일 결과는 변경 없는 코드의 기존 증거로 재사용했다.

[최신 PR CI39474405548](https://github.com/Kimgyuilli/PeekCart/actions/runs/39474405548)가 통과한 뒤 정상 병합한 SHA는 `af60cd6d9dceac541b6f457062093df70753f3ce`다. [main CI39476821899](https://github.com/Kimgyuilli/PeekCart/actions/runs/39476821899)는 E2E·gate와 여섯 publish를 포함해 성공했다. 완료 행은 상태 외 본문과 전체 ID 다중집합·유일성을 확인해 이동했다.

### D-090 경보 실행 계약 검사 완료

[PR186](https://github.com/Kimgyuilli/PeekCart/pull/186) 최종 head `789d13c9be36651ec0f243a28c6a55b308a890a6`은 Outbox 경보의 condition/refId, 평가 모델, for, 라벨, isPaused, instant/range 설정을 검사한다. 실제 운영 경보 규칙은 변경하지 않았다. [독립 최종 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/186#issuecomment-6298523288)는 최신 main 통합 후 작성 patch 동일성을 확인하고, 61개 경보 변이와 정상 대조·gate·matrix·ship 검증을 직접 실행했다.

[PR CI39476990835](https://github.com/Kimgyuilli/PeekCart/actions/runs/39476990835) 통과 후 정상 병합 SHA는 `545e5dc0bf64505f2bdb117f5d5248b85f17310f`다. [main CI39479945969](https://github.com/Kimgyuilli/PeekCart/actions/runs/39479945969)는 모든 검사와 여섯 publish가 성공했다. 상태 외 본문 보존과 전체 ID 다중집합·유일성을 확인해 완료 행을 이동했다.

### D-064 웹훅·409 무료 계약 보정 부분 병합

[PR193](https://github.com/Kimgyuilli/PeekCart/pull/193) 최종 head `184c19cfcc696c6758fee9bbde27568e50b80ce0`은 일반 PAYMENT_STATUS_CHANGED를 제한된 비신뢰 hint로 접수하고, 알려진 미결 원장에만 원자적인 중복 방지·조회 가속을 적용한다. 최종 결제 판단은 서버 PG 조회만 신뢰한다. 승인 409의 IDEMPOTENT_REQUEST_PROCESSING은 미결·조회 전용 처리로 분류한다. [초기 독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/193#issuecomment-6298254459)는 실제 MySQL 동시성·rollback을 포함한 86개 테스트를 실행했고, [최신 delta 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/193#issuecomment-6298901536)는 작성 patch 불변과 최신 main 통합·경량 검사를 확인했다.

[최신 PR CI39480119984](https://github.com/Kimgyuilli/PeekCart/actions/runs/39480119984) 성공 후 정상 병합 SHA는 `a7ffa11f24be2bd281d8952ac5fc5d351fb6119c`다. [main CI39483390397](https://github.com/Kimgyuilli/PeekCart/actions/runs/39483390397)는 모든 검사와 여섯 publish가 성공했다. 실제 PG·외부 웹훅 전달은 미검증이고 사용자 승인된 새 주문 ID 계약은 PR196에서 검증 중이므로 D-064는 열린 부분 완료로 유지한다. 운영 배포나 결제·환불은 실행하지 않았다.

### D-094 Redis 장애 시간 상한 진단 완료

[PR198](https://github.com/Kimgyuilli/PeekCart/pull/198) 최종 head `a0c2d3e7cd36f6153c020ee6df4aef5f8935c14e`는 실제 Lettuce timeout·네 경로 fallback 카운터·경과 시간을 실패 전에 남긴다. 기존 3500ms 상한은 유지했다. 실제 정상 2075ms, 1800ms 지연 변이 3885ms 실패, timeout 1초 변이 4070ms 실패, 원복 2056ms 통과의 원본 XML과 해시를 보존했다. [독립 최초 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/198#issuecomment-6299237478)와 [최신 통합 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/198#issuecomment-6299645860)는 원본·API 설정·변이와 복원 해시·최신 main 통합을 직접 대조했다. 리뷰어가 같은 Docker 실측을 재실행한 것으로 주장하지 않는다.

[최신 PR CI39484673767](https://github.com/Kimgyuilli/PeekCart/actions/runs/39484673767) 성공 후 정상 병합 SHA는 `ec677b376ff69b1e77c43abf4a073722a58c786f`다. [main CI39487364120](https://github.com/Kimgyuilli/PeekCart/actions/runs/39487364120)는 전체 검사와 여섯 publish가 성공했다. TASKS 완료 행 이동은 상태 외 본문, 전체 D-ID 다중집합과 유일성을 확인했다.


### D-064 PG 주문 식별자 무료 계약 부분 병합

[PR196](https://github.com/Kimgyuilli/PeekCart/pull/196)의 최종 head `6b2b534e8c512868a1330a3aaa8399c5ea84ca7a`는 사용자 승인에 따라 canonical PG 주문 ID를 생성·저장하고 checkout 준비 응답부터 승인·조회·복구까지 같은 ID를 유지한다. 기존 NULL 행은 다시 쓰지 않으며 외부 인증에 사용한 ID를 서버에서 임의 변경하지 않는다. 실제 PG와 외부 checkout 연결은 미검증이다.

[최종 독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/196#issuecomment-6300177654)는 E2E 클라이언트 계약 보완과 유한 timeout 진단을 확인했다. 작성자 실제 Compose A/E, 최종 product 236개 및 진단 음성 대조의 원본·hash를 대조했고, 리뷰어는 PG stub 41개와 smoke 59개를 직접 실행했다. 최초 product 동시성 barrier timeout 원인은 아직 미확정이며 D-078 무료 후속 분석으로 남긴다. 통과를 원인 해소로 주장하지 않는다.

[최신 PR CI39487696753](https://github.com/Kimgyuilli/PeekCart/actions/runs/39487696753)가 성공한 뒤 병합 SHA는 `a98ae7d51e3f266a0d9a89996f4a2dd2483af4eb`다. [main CI39568336863](https://github.com/Kimgyuilli/PeekCart/actions/runs/39568336863)는 전체 검사와 여섯 publish가 성공했다. D-064는 전체 완료로 이동하지 않고 부분 완료를 유지한다. 운영 배포와 실제 결제·환불은 실행하지 않았다.

### D-065 첫 적재 lineage writer 부분 병합

[PR201](https://github.com/Kimgyuilli/PeekCart/pull/201)은 ID 예약과 첫 INSERT의 root 기록을 같은 트랜잭션에 두고, 중복·예약 ID 충돌을 affected count와 저장 ID·좌표로 함께 구분한다. ID 간격은 허용하며 구형 writer 혼재를 안전하다고 주장하지 않는다.

최종 head `b288e3a1ff5975ed0edf2e26d02d0b286992a529`의 [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/201#issuecomment-6311540940)는 unit 4개와 실제 MySQL 67개를 직접 실행해 failure/error/skip 없이 통과했다. [PR CI39568431878](https://github.com/Kimgyuilli/PeekCart/actions/runs/39568431878) 성공 후 정상 병합 SHA는 `4a4a36a12a3d914d6bc0379ded94bc0123c4d370`다. [main CI39570795532](https://github.com/Kimgyuilli/PeekCart/actions/runs/39570795532)는 전체 검사와 여섯 publish가 성공했다. writer 범위만 부분 완료이며 offline fixture 제약 검증은 PR202, 실제 Flyway contract/restart와 signed gateway는 무료 후속으로 남는다. 운영 표본·GKE·운영 DDL·replay 개방은 실행하지 않았다.

## 앱·메시징·Saga·모니터링 10건 완료 (2026-10-17)

## 최종 부채·소유권·증거 대조

| ID | 작업자 | branch (debt/) | PR | 완료 증거 |
|---|---|---|---|---|
| D-083 | A | `foundation` | [#177](https://github.com/Kimgyuilli/PeekCart/pull/177) | cleanup 오류 전파 |
| D-068 | A | `foundation` | [#177](https://github.com/Kimgyuilli/PeekCart/pull/177) | MySQL 8.0.46·실행계획 |
| D-080 | A | `foundation` | [#177](https://github.com/Kimgyuilli/PeekCart/pull/177) | 원본 성능·셔플 사실 정정 |
| D-073 | B | `documentation→foundation` | [#177](https://github.com/Kimgyuilli/PeekCart/pull/177) | 원본 CPU/PR 대조 |
| D-077 | B | `documentation→foundation` | [#177](https://github.com/Kimgyuilli/PeekCart/pull/177) | 현행 계약 문서 |
| D-067 | A | `product-lane→saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | MVC 400/500 분류 |
| D-066 | A | `product-lane→saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | 정렬·tie-breaker·캐시 분리 |
| D-072 | A | `product-lane→saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | general_log hit0/miss 양성 |
| D-069 | A | `product-lane→saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | DB 상한·503·회복 |
| D-076 | B | `messaging→saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | retry jitter·drain564초 |
| D-071 | B | `messaging→saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | metadata/version·HTTP6반례 |
| D-061 | A | `saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | 실제 F·PG ledger·소비자 종착 |
| D-062 | A | `saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | 동일DLT rewind·원장1/attempt2 |
| D-070 | B | `monitoring→saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | get10m/evict0·규칙변이 |
| D-063 | B | `monitoring→saga-evidence` | [#180](https://github.com/Kimgyuilli/PeekCart/pull/180) | 실제35분·15instance·최종모델동등 |

모든 PR 독립 검증은 구현에 참여하지 않은 verifier가 수행했고 root만 병합했다. 각 개별 plan/audit는 원래 작업의 등급·ADR 판단·선행 의존성·검증을 보존한다. foundation은 최초 공용 기반이며 product lane은 D067→066→072→069, Saga는 D061→062, 실제 경보는 D070→063 순으로 검증했다.

| 열린 항목 | 처분 | 재개 조건 |
|---|---|---|
| D-075 | 사용자 명시 보류, 구현·실험 미수행 | 목표 TPS의 단위·대상 경로, 최대 backlog 시간과 초과 대응 결정 |
| D-084 | #181 안전 복구 부분 병합, 전체 미완 | 영구404에 대해 신뢰할 PG 발급 무과금 증빙 원천·형식 확인 |
| D-064 | 사용자 제외 | 실 PG 검증 범위와 무료 조건 별도 결정 |
| D-065 | 사용자 제외 | 운영 replay 개방 전제·GKE 검증 별도 승인 |
| D-078 | 사용자 제외 | GKE 측정·비용·운영 조건 별도 승인 |

대상16건 중14건과 조건부D063을 합한15건만 완료로 기록한다. D075/D084와 제외3건은 열린 TASKS에 남기며 계획을 아카이브하지 않는다.

## 최종 병합·검증·완료 기록 (2026-10-17)

[PR180](https://github.com/Kimgyuilli/PeekCart/pull/180)의 head `2dde3244e7419fa64a0cb52ef28a17c72c71486c` / base `d405cfabfbe03ecf4b06a32ef2e6a400e7b29988`를 독립 검증했다. [CI38987011757](https://github.com/Kimgyuilli/PeekCart/actions/runs/38987011757)의 실제 checkout `e98ab23a16f733fee6bfe832e4fb298bb047484f` 부모와 필수17jobs/gate, XML244/1372tests 실패·오류·skip0, A–F 동일SHA·이미지 identity·matrix35·음성234초 증거를 대조했다. D061 F110.9초의 실제 호출한 PG stub의 POST/GET ledger와 UNRES 생산outbox0/합성 소비 구분, D062 DLT p0/o2 committed3→rewind2·group Empty·동일원장1행/attempt2를 확인했다. D063 실행460의 대상5rule full model은 최종head와 동일하다.

root가 보호 규칙을 지켜 병합한 SHA는 `1178071e9bbffec77676fa0e2f0bee79de8eadc2`다. [main CI38988468800](https://github.com/Kimgyuilli/PeekCart/actions/runs/38988468800)는 동일 실제checkout, 23jobs/6publish SUCCESS, XML244/1372tests 실패·오류·skip0, 양E2E 이미지 identity·A–F·음성238초를 통과했다. main D70초/F120초 및 전체 job45분 제한 준수와 공개GHCR6서비스 SHA/latest manifest·config 일치를 독립 확인했다.

별도 codex exec 리뷰 미호출; 구현에 참여하지 않은 독립 PR 서브에이전트 검증 PASS. 무료 조건은 공개 저장소·표준 runner·GHCR 및 사용자의 artifact/cache 확인으로 충족했다. 기존 두 lint 실패는 정확한 원인과 수정·반례를 audit에 보존했고, 이전 실패/skip/오래된 결과로 병합하지 않았다. PR의 main 전용 publish 미실행과 실제 main6publish 성공을 구분했다.

이 항목의 구현·검증·병합·main 확인이 끝나 TASKS 완료 행·PHASE·개별 plan/audit와 선택 아카이브에 반영한다. 이 문서 기록의 전달은 별도 `debt/final-records` PR이 담당하며 그 PR 자체의 최종 CI·병합·main 증거는 해당 PR 본문에 이어 기록한다.

## 서버 확정 조회 복구 부분 병합·검증 (2026-10-17)

[PR181](https://github.com/Kimgyuilli/PeekCart/pull/181)의 head `9f763181117a12a498943c09ef315ffb93e2f652` / base `850b0d448ebc6e46524aa2576545b3d55c1ff9d6`를 독립 검증했다. [PR CI38983400228](https://github.com/Kimgyuilli/PeekCart/actions/runs/38983400228)의 실제 checkout `01c9d5b6c456d92ddaa2aa9bf35ce05055477bc0` 부모와 artifact를 대조했다. 필수17jobs/gate, XML234/1336tests 실패·오류·skip0, Recovery18(추가 반례6 포함), 양E2E A–E/이미지 identity/음성 대조군 PASS.

root가 보호 규칙을 지켜 병합한 SHA는 `d405cfabfbe03ecf4b06a32ef2e6a400e7b29988`이다. [main CI38984838008](https://github.com/Kimgyuilli/PeekCart/actions/runs/38984838008)의 실제 checkout도 동일하며 23jobs/6publish, XML234/1336tests 실패·오류·skip0, 양E2E 및 공개 GHCR6서비스 SHA/latest manifest·config 일치를 독립 확인했다. 별도 codex exec 리뷰 미호출; 독립 PR 서브에이전트의 최신 head/base·실제 checkout·artifact 검증 PASS. 기존 A–E는 회귀 검증이며 새 ADMIN 분산 복구 체인 전체 E2E 실행으로 주장하지 않는다. 새 계약은 집중 실제 DB·서명 HTTP·직렬화 consumer/inbox 및 CI 반례로 검증했다.

ADR-0040은 서버가 직접 확인한 확정 PG 결과의 제한된 복구 결정으로 Accepted 전환한다. 영구404에 대한 외부 신뢰 증빙 원천·형식은 미확보이므로 D084 전체는 미완이다. TASKS 열린 행과 개별 계획을 유지하며 자동 아카이브하지 않는다. 기록 커밋 자체의 후속 PR 반영은 계속 진행한다.

## 부채 기반·문서 5건 완료 (2026-10-17, [#177](https://github.com/Kimgyuilli/PeekCart/pull/177))

- D-083: 토픽 부재만 생략하고 timeout·권한 오류·중단을 전파한다. 집중 회귀 4건과 전체 CI를 확인했다.
- D-068: MySQL 실행 선언 5곳을 8.0.46으로 정합화했다. drift 반례 27건과 실제 실행계획 4건 및 전체 CI를 확인했다. 실제 k8s 배포는 범위 밖이다.
- D-080: 원본 XML·로그·jstack·바이트코드로 기동/종료 비용과 객체 기반 캐시 키를 정정했다. 셔플 이전 결함4 실패를 원문 및 PR138에 보존 정정했다.
- D-073: CPU 표본과 처리량 런, 코드 호출 체인과 PR119/120 이력을 원본 대조해 정정했다. 블로그28편은 이미 정확해 외부 수정하지 않았다.
- D-077: 현재 낙관락·jitter 재시도·비동기 예약 해제 계약으로 Layer1·swagger·주석을 맞췄다.

2026-10-17 [PR177](https://github.com/Kimgyuilli/PeekCart/pull/177) 병합 SHA `850b0d448ebc6e46524aa2576545b3d55c1ff9d6`. PR head `cb58da0944fc49ee14756f307328662f8bcca39a`, base `3e1ba64c715d22b1f5a2d2635a97da17eb4ebb28`, 실제 checkout `4bde8cced2ff8b4ec43bc6a476bcda59ef4cbdbb`의 [CI38981159191](https://github.com/Kimgyuilli/PeekCart/actions/runs/38981159191)는 필수17jobs/gate와 XML229/1296tests 실패·오류·skip0, 양E2E 이미지 identity/동일SHA A–E/음성 대조군을 통과했다. 독립 verifier가 최신 head/base와 실제 checkout 부모, artifact를 대조해 PASS를 기록했다.

병합 후 [main CI38982636776](https://github.com/Kimgyuilli/PeekCart/actions/runs/38982636776)는 실제 checkout850b0d4, 같은1296tests와 양E2E, gate를 포함한23jobs 및6publish 모두 성공했다. 공개 GHCR6서비스의 SHA태그/latest manifest digest와 config 일치도 독립 확인했다. root만 보호 규칙을 지켜 병합했으며 별도 codex exec 리뷰는 호출하지 않았다. 무료 조건은 공개 저장소·표준 runner·GHCR 확인 및 사용자의 artifact/cache 무료 범위 확인으로 충족했다.

기존 ADR-0025/0027/0028의 결정은 유지하고 사실 정정만 Update Log에 추기했다. #175/#176의 완료 계획6건과 진단 #179도 실제 병합·main 검증을 확인해 아카이브했다. 이전 절의 PR 대기 문구는 당시 기록이며 이번 확인으로 갱신한다. D-064/065/078은 제외·열림, D-075는 사용자 보류, D-084와 나머지 검증 대기 항목은 완료로 처리하지 않았다.

## D-060 완료 ([#175](https://github.com/Kimgyuilli/PeekCart/pull/175), 2026-10-14)

HTTP 202로 승인 접수 후 Product의 예약 보호 회신을 받아 PG를 호출한다. 일반 취소·만료 회수는 보호된 예약을 해제하지 않고 확정 실패 또는 성공 환불로 재고를 복구한다 (see ADR-0034).

검증: 커밋 `4b7a354`, [CI 38457128697](https://github.com/Kimgyuilli/PeekCart/actions/runs/38457128697)의 전체 모듈 테스트, E2E 및 최종 증적 gate 통과. 로컬 CPU 포화로 CI 검증으로 이전했다. 계약 ID 표기 수정과 의존성 조회 실패 재실행 이력은 D-060.audit.md에 기록했다.

미충족: D-060 구현·검증 범위 없음. 실 PG 검증은 D-064, 환불 결과 전체 E2E 확대는 D-061이다. PR은 리뷰 대기이며 머지·운영 배포는 수행하지 않았다.

## 기술 부채 PR 통합 보강 ([#176](https://github.com/Kimgyuilli/PeekCart/pull/176), 2026-10-17)

원격 main의 D-060 PR #175(cb8056d)를 기준으로 보강한다. 앞선 1f42c4f 기반 별도 구현과 1,275건 테스트 결과는 이번 PR의 검증 근거로 사용하지 않는다. 기존 상태·토픽·Product V11·Payment V12와 replay DENY를 유지하고, 보호 승인 수동 감사 뒤 조회 복구 유지 및 승인 원장 상태 조회를 보강한다(ADR-0035).

D-081·D-082 리스너 할당 대기와 writer 수명 lint, D-079 측정 overlay 기본 렌더, D-074 ADR 제목 정정을 함께 통합한다. #175의 replay 버전 v1 감사 기대값을 유지한다. 최신 기준 전체 테스트 1,292건(실패·오류·skip 0), 아키텍처 가드 5개, JVM/E2E 증거 매트릭스 34행, 최신 이미지 E2E a~e와 실제 Payment 재시작 검증을 완료했다. 최초 E2E의 Location 헤더 누락은 API slice 회귀로 재현하고 수정 후 전체 JVM/E2E를 재실행했다. 영구 404의 증빙 기반 운영 해제와 이미 수동 FAILED인 보호 건의 자동 복구는 제공하지 않는다(ADR-0035).

## Phase 5 는 무엇이 다른가

Phase 1~4 는 **착수 전에 순서표가 있었다.** 무엇을 언제 할지 로드맵
(`docs/07-roadmap-portfolio.md §16`)에 미리 적어 두고 그 순서를 소진하는 방식이었고,
순서가 비면 단계가 닫혔다.

Phase 5 에는 그 순서표가 없다. **필요하다고 판단한 시점에 항목을 추가하고 그때 착수한다.**

그래서 세 가지가 바뀐다:

1. **Exit Criteria 가 없다.** 소진할 목록이 없으므로 단계가 종결되지 않는다.
   `docs/TASKS.md` 의 열린 표가 곧 현황이다
2. **추적 축이 하나다.** D- 번호 단일 표. "부채"와 "신규 작업"을 나누지 않는다 —
   수요 기반에서는 둘 다 *지금 필요하다고 판단한 작업* 이라는 같은 성격이다
3. **동기를 기록하지 않으면 복원되지 않는다.** 로드맵이 근거를 대신 붙들어 주던 것이
   사라졌으므로, 항목을 추가할 때 **왜 필요한지**를 `요약` 에, **어디서 발견했는지**를
   `묶음` 에 적는다. 이것이 Phase 5 에서 유일하게 늘어난 규율이다

## D-059 — 환불 처리 중 응답의 재시도 분류 ([#174](https://github.com/Kimgyuilli/PeekCart/pull/174), 2026-10-14)

Toss 취소가 `409 IDEMPOTENT_REQUEST_PROCESSING`을 반환하면 TRANSIENT로 분류한다.
기존 동일 멱등키 재시도와 UNRESOLVED 조회 복구 경로를 사용하며, 일반 409와
ALREADY_CANCELED 분류는 유지한다(see ADR-0018 D3/D5).

검증: 수정 전 핵심 회귀 3건 실패 확인. 수정 후 집중 검증 39건과 payment 전체 247건 통과.
`./gradlew test` 성공, 변경 없는 다른 모듈은 UP-TO-DATE 결과 재사용.
실제 MySQL에서 미확정 원장 보존·회신 없음과 후속 조회 성공 시 회신 1건을 확인했다.
통합 테스트 컨테이너 규약 lint 및 문서 consistency precheck 통과. 별도 Codex 리뷰 미호출.

미충족: 실 Toss 호출 및 처리 지연 빈도는 D-064에서 검증한다. 승인 분류와 다른 PG 오류 코드는 범위 밖이다.
선행 문서 PR #173의 부채 등록을 포함하며 #173 이후 머지 대상이다.

## 이월 항목 (Phase 4 → 5)

| ID | 영역 | 상태 |
|---|---|---|
| D-027 | Harness / Cost | ✅ ② 완료([#128](https://github.com/Kimgyuilli/PeakCart/pull/128)) · ① 완료([#158](https://github.com/Kimgyuilli/PeekCart/pull/158)) · ③ 완료([#159](https://github.com/Kimgyuilli/PeekCart/pull/159)) |
| D-030 | Observability / Ops | ✅ 완료([#155](https://github.com/Kimgyuilli/PeekCart/pull/155)) — Outbox `FAILED` ↔ DLQ Slack 채널 분리 + DLQ 적재량 메트릭 (L-004 승격, [#133](https://github.com/Kimgyuilli/PeakCart/pull/133)) |

---

## 작업 이력

> 엔트리 형식은 PHASE4.md 와 동일: `## <제목> ([PR](...), YYYY-MM-DD)`

## plan · work 공통 Codex 리뷰 규칙의 단일 문서화 (D-057, [#172](https://github.com/Kimgyuilli/PeekCart/pull/172), 2026-09-30)

`.claude/commands/plan.md` 와 `work.md` 에 두 벌 있던 Codex 호출 게이트, 결과 처리, 라운드 2 규칙, 리뷰 상태 4값을
`docs/conventions/codex-review.md` 로 옮겼다. D-027 ① 이 남은 크기의 주원인으로 지목하고 이월한 부분이다. 두 커맨드
합계는 29,995B 에서 20,561B(31% 감소)다. 공통 문서는 Codex 를 부를 때만 읽으므로, 읽지 않아도 지켜야 할 두 규칙(승인 없는
호출 금지, 렌더러 exit 2 는 미결)은 커맨드 본문에 한 줄씩 남겼다. 절 제목은 `harness-rationale.md` · `writing.md` ·
`codex-review-render.sh` · ADR-0028 이 가리켜 고정했다. 검증 스크립트는 실패 주입으로 두 번 보정했다. 원문 그대로 옮긴 줄까지
예외로 빼던 것을 좁혔고, 절 이름 접두어 비교를 정확 일치로 바꿨다. 등급 M, diff 리뷰는 `.cache/codex-off` 로 의도적 생략.

미충족: 5-1 호출 프롬프트는 두 커맨드가 역할과 체크 항목이 달라 옮기지 않았다.

## Layer 1 문서의 Phase 1·2 설계 아카이브 분리 (D-053, [#171](https://github.com/Kimgyuilli/PeekCart/pull/171), 2026-09-30)

`docs/02-architecture.md` §5·§12 의 Phase 1 다이어그램, Phase 1·2 패키지 구조, Phase 1 대비 전환 비교표와
`docs/05-data-design.md` §11 의 Phase 1 ERD, Phase 2 변경점, 비교표를 `docs/progress/design-archive-phase1-2.md` 로 원문
그대로 옮겼다(02 는 556 에서 344줄, 05 는 490 에서 308줄). 두 문서를 가리키는 34개 파일이 모두 절 번호나 남는 하위 절을
가리켜 절 제목만 유지하면 참조가 깨지지 않는다. D-052 미충족이던 `consistency-hints.sh` 항목 4 는 찾을 대상이 없어져
지웠다. 보존 검증은 처음에 줄 집합 비교로 짰다가, ERD 의 반복 줄 하나를 지우는 주입을 놓치는 것을 보고 줄 개수 비교로
바꿨다. 등급 M, diff 리뷰는 `.cache/codex-off` 로 의도적 생략.

미충족: §12 Phase 3 절의 디렉터리 트리가 지금은 없는 루트 `src/` 를 그린다. 현행 배포 순서의 정본이라 옮기지 않았다.

## TASKS.md 완료 항목 아카이브와 완료 행 이동 절차 (D-052, [#170](https://github.com/Kimgyuilli/PeekCart/pull/170), 2026-09-30)

`docs/TASKS.md` 153KB 중 열린 행은 1.4KB 였다. 종결된 Phase 4·버킷 1·보류 섹션은 `docs/progress/TASKS-archive-phase4.md`
(고정 스냅샷)로, 완료 D- 행 44개는 `docs/progress/TASKS-done.md`(계속 자라는 목록)로 원문 그대로 옮겨 `TASKS.md` 는 4KB 가
됐다. 한 번 옮기는 것으로는 다시 커지므로 완료 절차(`ship.md` Step 8 · `done.md`)가 완료 행을 `TASKS-done.md` 맨 아래로
옮기게 바꿨다. 이 PR 의 D-052 행이 첫 적용이다. ADR 이 D- 번호로 가리키는 행은 ADR 을 고치지 않고 `TASKS.md` 부채 섹션
머리의 포인터로 한 단계 따라가게 했다. 착수 전 코드 검증에서 `hpx_ship_resume_point` 가 PR 번호 숫자만 grep 해 D-053 행의
"170줄" 에 걸린다는 것이 드러났다. 이 PR 이 #170 이었고, 링크 꼬리(`/pull/<n>)`) 매칭으로 바꾼 뒤 Step 8 전 판정이 정상적으로
"/done 미적용" 을 냈다. `harness-context.sh` 완료 카운트는 두 파일 합이 되어 60 에서 44 로 바뀐다(Phase 4 표 16행은 세지 않음).
검증 V1~V6(줄 보존·ID 보존·다이제스트·판정 식·링크·잔존)을 각각 실패 주입으로 확인했다. 등급 L, 계획·diff 리뷰는
`.cache/codex-off` 로 의도적 생략.

미충족: `docs/consistency-hints.sh` 항목 4 는 옛 Phase 3 형식 줄을 찾는다. 이전에도 0건이라 그대로 두고 D-053 때 본다.

## 코드 주석의 계획서 내부 식별자 제거 (D-050, [#169](https://github.com/Kimgyuilli/PeekCart/pull/169), 2026-09-30)

main 코드 주석 약 500줄에서 계획서 안에서만 통하는 식별자(`§2.5`·`P15`·`④-c-2b-4a`·`PR3d`·`계획 리뷰 2R #5`, 게이트 라벨
`GW-2 c2:1`, 출처 없는 `B6`, `strangler-N`)를 지우고, 근거 역할을 하던 곳은 근거를 풀어 썼다. 번호 없는 ADR 절은
`ADR-0020 §D6-3`·`ADR-0022 §D2` 처럼 한정했고, 줄번호 참조 `ADR-0012:48` 은 절(`D2`)로 바꿨다. `D-0xx`·`L-0xx`·ADR
정의 번호는 유지했다. 작업 중 지금 코드와 사실이 다른 서술이 드러나 함께 고쳤다(서비스 `build.gradle` 의 "Flyway 런타임
disabled", gateway 의 "HS512 fallback", "전환기 root 가 토픽 소유", 삭제된 `JwtTokenVerifier` 참조). Flyway 마이그레이션
SQL 은 migration 체크섬 근거를 확인하며 범위 밖으로 뒀다(당시 로컬 `flyway-core 12.4.0` jar; 프로젝트의 payment runtime 버전 및 줄 처리 범위는 D-100 사실 보정 참조). 검증은 식별자 검사와 "주석 외
변경 = 문자열 4건" diff 검사를 각각 실패 주입으로 확인한 뒤 적중 0, `./gradlew build` 1255 테스트 0 실패. 등급 M, diff
리뷰는 `.cache/codex-off` 로 의도적 생략.

미충족: `src/test` 주석은 범위 밖이라 그대로다. 검사 스크립트는 로컬에만 있어 재발 방지 장치가 없다. 새 계획 식별자가
주석에 다시 쌓이면 CI lint 로 상시화한다.

사실 보정(D-100, 2026-10-21): 위 기록의 `flyway-core 12.4.0`은 D-050 작업 때 실제 프로젝트에서 해석한 버전이 아니었다. #169 직전 `42030ff` 및 기준 코드 `be535c57`의 payment `runtimeClasspath`의 버전은 `11.7.2`다. 11.7.2와 12.4.0의 `ChecksumCalculator` jar 실험은 동일한 입력별 checksum을 냈다. 구현은 `readLine()`의 줄 텍스트를 UTF-8 CRC32에 넣으며, 8개 입력 실험에서 주석 텍스트 및 공백-only 줄 변경은 checksum을 바꿨고, 빈 줄·LF/CRLF·마지막 개행 차이는 바꾸지 않았다. 이는 isolated `StringResource` calculator 실험이며 데이터베이스의 `flyway validate`를 실행한 결과가 아니다. 따라서 migration SQL을 제외한 D-050 범위 결론은 유지되지만, 기존 DB에서 validate가 실패했다는 주장은 이 실험으로 확정하지 않는다. 상세 근거는 [D-050 계획의 D-100 사실 보정](../plans/done/D-050.md#d-100-사실-보정)에 기록했다.

## outbox·멱등성·DLQ 원장 실행 세트의 공유 모듈 승격 (D-049, ADR-0033, [#168](https://github.com/Kimgyuilli/PeekCart/pull/168), 2026-09-29)

4서비스에 byte 동일하게 복제된 `global.{outbox,idempotency,deadletter}` 와 `ShedLockConfig` 32개를 새 모듈
`peekcart-common-messaging` 으로 옮기고 사본 96개를 지웠다. 서비스마다 다른 `DeadLetterConsumer`·
`DeadLetterQuarantineConsumer`·`LedgerOwnerConfig` 와 Flyway 스키마는 서비스에 남겼다. `:common` 편입은 user-service 가
엔티티·스케줄러를 스캔하게 돼 기각했다(ADR-0033 Alt A). `OutboxEventStatus` 는 `BACKFILL` 을 포함한 한 벌로 합쳤다.
재복제 방지는 parity lint 의 java byte 검사에서 `assertNoDuplicateGlobalFqcn` 으로 옮겼다. 코드 검증에서 CI test 매트릭스와
`integration-test-container-lint` 모듈 목록도 갱신 대상으로 드러나 범위에 넣었다. 같은 PR 에 머지된 계획서 5건의
아카이브가 함께 들어 있다. 등급 L, 계획·diff 리뷰는 `.cache/codex-off` 로 의도적 생략.

미충족: 서비스별 `global.*` 통합 테스트(`DeadLetterLedger`·`DeadLetterMetrics`·`LedgerOwnerWiring`)는 4벌 그대로다.
공유 로직을 바꿀 때 4벌을 함께 고치는 일이 반복되면 다시 본다.

2026-10-21 D-096 사실 정정: #168 직전 `7e83ee3`의 32개 공유 후보 중 31개만 네 벌 byte 동일했다.
`OutboxEventStatus`는 `BACKFILL` 유무에 따라 order·product와 payment·notification 두 집합이다. 원본 소스의
outbox·idempotency·deadletter·`ShedLockConfig` 조사 범위의 서비스별 파일 수는 35/35/35/34다. 이는 전체 `global` Java
파일 수가 아니다. 전체 수는 35/35/37/34이며 product에만 `global/config/CacheConfig.java`와 `ResilientCacheErrorHandler`가 추가된다.
`DeadLetterConsumer`·`DeadLetterQuarantineConsumer`·
`LedgerOwnerConfig`는 서비스 고유다(notification에는 quarantine consumer가 없다). BACKFILL migration은
order V5와 product V4다. 당시 Java parity lint는 deadletter 16개, outbox 8개, enum 두 집합을 검사했고,
idempotency 6개와 `ShedLockConfig`까지 byte 동일성을 강제하지 않았다. 상세 근거는 D-049 계획 addendum과
ADR-0033 Update Log에 기록했다.

## Kafka 리스너 통합 테스트의 할당 대기와 발행 동기화 (D-058, [#167](https://github.com/Kimgyuilli/PeekCart/pull/167), 2026-09-29)

#164 CI 에서 payment `DlqIntegrationTest` 가 20초 동안 DLT 레코드 0건으로 실패한 건의 후속이다. 같은 구조(리스너를
켜고 곧바로 발행)가 4모듈 12개 클래스에 있어 범위를 넓혔다. `AbstractIntegrationTest#awaitListenerAssignment` 로 발행 전
파티션 할당을 기다리고, future 를 버리던 send 22곳을 `orTimeout(10초).join()` 으로 동기화했다. 누락은
`integration-test-container-lint.sh` ITC-006 이 막는다. 계측해 보니 로컬에서는 테스트 시작 시점에 할당이 이미 거의 끝나
있어(대기 약 0.1초) 초안 전제인 "할당 지연이 첫 테스트를 느리게 한다" 가 반증됐다. 등급 L, 계획·diff 리뷰는
`.cache/codex-off` 로 의도적 생략.

미충족: CI 실패의 근본 원인은 미확정이다. 재발하면 할당 대기 timeout 이나 발행 지점 예외로 좁혀진다. 그래도 await
timeout 으로 실패하면 CI 에 앱 로그를 보존하는 task 를 연다.

## 미배선 스크립트 2개의 CI 편입 (D-056, [#166](https://github.com/Kimgyuilli/PeekCart/pull/166), 2026-09-29)

`scheduler-lock-contract-lint.sh` 와 `codex-review-render-selftest.sh` 를 lint 잡의 policy lints 단계에 넣었다. 삭제도
후보였으나 scheduler lint 는 D-024 풀 확대 뒤 cron 잡 자기 중첩을 막는 유일한 강제 수단이고, render selftest 는 리뷰
렌더러의 false-green 방지 장치라 배선을 택했다. 각각 실패를 주입해(`@SchedulerLock` 제거, 빈 응답 판정 exit 0) exit 1
을 확인했다. render selftest 의 V9 는 `.cache` 를 읽으므로 CI 에서는 skip 된다. 등급 M, diff 리뷰는 `.cache/codex-off` 로
의도적 생략.

미충족: scheduler lint 에는 `--self-test` 가 없다. 위반 탐지는 이번 실패 주입으로만 확인했고, lint 자체를 무력화하는
수정은 CI 가 잡지 못한다. 재검토 조건은 이 lint 의 검사 분기를 고치는 PR 이 생길 때다.

## 잔존 audit 디렉터리의 done 병합 (D-055, [#165](https://github.com/Kimgyuilli/PeekCart/pull/165), 2026-09-29)

이전 하네스가 쓰던 `docs/plans/.audit/` 의 파일 2개를 `done/` 의 같은 이름 audit 에 병합하고 디렉터리를 없앴다. 두 쪽
기록은 겹치지 않았다. gateway 는 잔존본이 PR1~PR3b(#73~#76), `done/` 본이 PR3c(#77)였고, pr3d 는 잔존본이 2026-07-24
계획 리뷰 3라운드, `done/` 본이 구현 이후 기록이었다. 병합 전 두 쪽의 모든 줄이 병합본에 남은 것을 `grep -vxFf` 로
대조했다. 같은 PR 에서 #164 CI 실패 분석의 부채 D-058(Kafka 통합 테스트 토픽 부재 경합)을 등록했다. 등급 S.

미충족: 없음.

## Notion 설계 export 원본 삭제 (D-054, [#164](https://github.com/Kimgyuilli/PeekCart/pull/164), 2026-09-29)

`docs/00-lagacy.md` (68KB) 를 삭제했다. 01~07 분리 때 내용이 전부 이관됐고 원본은 git 이력이 보존한다. archive
이동도 후보였으나 파일이 남으면 grep 중복이 계속돼 삭제를 택했다. PHASE1·PHASE4·TASKS-archive·done 계획서의 언급은
당시 사실이라 고치지 않았다. 등급 S.

미충족: 없음.

## Layer 1 문서와 CLAUDE.md 의 코드 불일치 정정 (D-051, [#163](https://github.com/Kimgyuilli/PeekCart/pull/163), 2026-09-29)

`02-architecture.md` Phase 4 트리와 전환표가 적던 `common/event`·`common/outbox` 공유 모듈과 `*EventProducer` 를 실제
배치로 바꿨다. 착수 전 검증에서 TASKS 원문과 달리 `common` 모듈은 존재하고 이벤트 DTO 는 `global/outbox/dto/` 에서
공유되며, 서비스별 복제는 Outbox·멱등성·DLQ 원장뿐임을 확인했다. `04-design-deep-dive.md` §8-3 의 "DLQ 에서 Slack 알림"
은 §9-3 과 같게 원장 적재와 `peekcart-dlq-backlog` alert 를 주 신호로 고쳤다(see ADR-0018 D6). CLAUDE.md 명명 규칙은
실제 consumer 9개의 패턴인 `{구독 대상}Consumer (infrastructure/kafka)` 로 바꿨다. 등급 S.

미충족: 없음. §8-3 에 남은 계획서 식별자(`④-c-2a` 등)는 D-050 범위라 두었다.

## 등급 판정 기준의 사후 검증 (D-027 ③, [#159](https://github.com/Kimgyuilli/PeekCart/pull/159), 2026-09-29)

`grade:` 가 적힌 계획서 25개(S 8 · M 11 · L 6)를 head 브랜치명으로 PR 에 매칭하고, `docs/` 를 뺀 diff 에 `/plan` §1 의
세 기준(모듈 2개 이상 · 계약 표면 · 작업 밖 되돌림)을 다시 적용했다. 계획서 안 첫 PR 링크는 6건이 다른 PR 을 인용해
매칭 근거로 쓰지 않았다. 25개 전부 일치했고, 재판정 2건(D-031 S에서 M, codex-review-render M에서 L)은 모두 범위
증가로 인한 상향이다. 판정 기준은 유지하고 사후 검산은 자동화하지 않았다. 크기는 M 0~617줄, L 115~1228줄로 겹쳐
크기 임계값 검사기는 올바른 판정을 오탐한다. 결과 표는 `docs/plans/done/task-d027-grade-audit.md` §4.

미충족: 판정자가 등급을 매긴 쪽과 같은 에이전트라 "전부 일치" 는 불일치 부재의 증명이 아니다. 리뷰 강도 배분의
타당성은 `.cache/codex-off` 로 재료가 없어 검증하지 못했다. 재검토 조건은 Codex 재개 후 M 등급 diff 리뷰의 P0 발생이다.

## 커맨드 근거 서술의 별도 문서 분리 (D-027 ①, [#158](https://github.com/Kimgyuilli/PeekCart/pull/158), 2026-09-29)

`/plan`·`/work`·`/ship` 은 호출마다 본문 전체가 컨텍스트에 실린다. 재개 조건은 H1~H6 규칙의 안정이었고, 커맨드가
2026-09-22 이후 바뀌지 않은 채 커밋 93개를 거쳐 충족으로 판정했다. 근거 서술 18곳(축소 이력, 실측 수치, 사건 이력)을
`docs/conventions/harness-rationale.md` 로 줄 단위 원문 그대로 옮기고 원래 자리에는 규칙과 `(근거 R-...)` 포인터만 남겼다.
절 번호는 `.agents/skills`·`writing.md`·D-040 이 참조하므로 제목 줄을 고정했다. 삭제 줄이 모두 근거 문서에 있는지
검사했고, 한 줄을 지우는 주입에 실패를 확인했다.

효과는 46,556B→43,219B(7%)로 작다. 남은 크기의 대부분은 `plan.md`·`work.md` 의 §5-0 게이트, §6 결과 처리,
리뷰 상태 4값이 거의 같은 문장으로 중복된 것이다. 같은 PR 에 D-038(Redpanda 재평가) 폐기 기록을 실었다.

미충족: 중복 규칙 통합은 근거 분리 범위가 아니라 이월했다. 재검토 조건은 커맨드 크기를 더 줄일 필요가 생길 때다.

## Gradle 구간 실측과 setup-gradle 미착수 판정 (D-039, [#157](https://github.com/Kimgyuilli/PeekCart/pull/157), 2026-09-29)

ADR-0028 §후속 ③ 의 "컴파일 60초" 는 D-032 이전 추정이라 착수 전에 다시 쟀다. PR run 36422217146(#156)과
36393965323(#154)에서 test 잡은 임계경로(e2e negative-control)보다 5.7~7.2분 먼저 끝난다. `setup-gradle` 이 닿는
곳이 여기라 줄여도 PR 시간은 줄지 않는다. 임계경로 위의 Gradle 은 Dockerfile 안 `bootJar` 이고 35.6초 중
컴파일이 약 8초, `--no-daemon` 기동·설정이 약 28초다. 이 캐시는 docker build 안에 닿지 않는다. 구현하지 않고 닫았다.

재개 조건: test 잡이 임계경로에 올라오거나 러너 시간 비용이 제기될 때.
미충족: 이미지 빌드의 Gradle 기동·설정 약 28초는 다른 수단(BuildKit cache mount 등)의 문제라 범위 밖이다.

## e2e 스택 기동의 토픽 생성 통합과 앱 동시 기동 (D-048, [#156](https://github.com/Kimgyuilli/PeekCart/pull/156), 2026-09-28)

D-042 시간 분해의 후속이다. main CI 3회에서 토픽 사전 생성이 65~68초, 앱 순차 기동이 18~25초씩 4회였다.
토픽 20종마다 생성과 대조를 따로 exec 해서 exec 와 Kafka CLI JVM 이 40회 떴다. 이를 exec 각 1회로 묶었고
생성 수·파티션 대조 가드는 유지했다. 결함 주입 3건(파티션 불일치, 생성 거부, 파서 고장)이 모두 rc=1 이다.
컨테이너 안 병렬 생성은 브로커 `-Xmx1G` 가 `mem_limit: 1g` 를 채우고 있어 채택하지 않았다.

앱 4개는 서로 `depends_on` 이 없어 compose up 1회로 동시 기동한다. 순차 기동의 사유는 2026-08-28 CPU 경합
실측 하나였다. 로컬(8 CPU) 실측은 토픽 50→23초, 앱 74→51초다. 두 잡의 기동 중복은 D-043 병렬 계약 때문에 범위 밖이다.

미충족: 앱 동시 기동의 채택 판정. CI 는 PR 에서만 돌아 PR 의 CI 2회 이상으로 판정하고, 창 초과가 한 번이라도
나면 순차로 되돌린다.

판정(2026-09-29, [#157](https://github.com/Kimgyuilli/PeekCart/pull/157)): 채택. 동시 기동 PR CI 2회가 녹색이고 창 초과가 없다.
기동 합계는 순차 기준선(run 36393965323) 156/178초에서 133/132초(#156) · 131/121초(#157)가 됐다(scenarios/negative-control).
#156 이 판정 전에 머지돼 두 번째 run 은 #157 에서 얻었다.

## Outbox 발행 소진 alert 신설과 DLQ 신호 분리 (D-030, [#155](https://github.com/Kimgyuilli/PeekCart/pull/155), 2026-09-28)

L-004(Phase 4 운영 관측성) 이월분이다. 착수 전 코드 검증에서 D-030 의 두 전제가 뒤집혀 범위를 다시 정했다.
DLQ 적재량 메트릭은 이미 있었고, 앱의 Slack 직접 발송은 notification-service 외 3서비스에서 no-op 이라
"한 채널에 섞인다" 는 notification-service 에만 해당했다. 실제 공백은 Outbox FAILED 에 alert 가 없는 것이었다.

`grafana-alerts.yml` 에 `peekcart-outbox-failed`(4서비스 `outbox_backlog{status="failed"} > 0`, for 5m)를 더하고
`signal` 라벨(`outbox-publish`/`dlq`)로 두 규칙을 갈랐다. FAILED 는 자동 재시도도 retention 삭제도 없는 종착
상태라 gauge 가 저절로 0 이 되지 않는다. `observability-promql-lint` 에 식 계약을 등록하고 self-test 를 17종에서
19종으로 늘렸다. 계약 추가 전에는 새 규칙이 검사 없이 통과했다. ADR-0009 S8 의 "alert 미도입" 서술은
작성 시점의 사실이라 고치지 않는다.

미충족: contact point / notification policy provisioning(ADR 판단 대상), Outbox FAILED 재발행 runbook,
notification-service 웹훅의 사용자·운영 알림 혼재, 실제 스택 alert 발화 시험(GKE 세션 필요).

2026-10-17 후속: D063/PR180에서 무료 로컬 실제 Prometheus/Grafana 평가의 동등성을 확인하고 원래 for의 발화·정상복귀를 검증했다. GKE 발견·sidecar·실앱방출·외부 알림 전달은 검증하지 않았으며 위 문장은 당시 미충족 이력이다.

## e2e 만 잡던 결함 3건의 JVM 단언 (D-047, [#154](https://github.com/Kimgyuilli/PeekCart/pull/154), 2026-09-28)

ADR-0032 D3 의 후속이다. D-042 결함 주입에서 모듈 테스트 전체가 통과한 3건에 JVM 단언을 더했다.
`PaymentControllerTest` 는 FAILED 응답의 코드 `PAY-005` 를 보고, `NotificationConsumerTest` 는
`payment.failed` 의 `PAYMENT_FAILED` 알림과 `reason=RESERVATION_FAILED` 취소 알림을 본다. `src/main` 변경은 없다.

M12·M13·M14 를 다시 주입하고 모듈 전체를 돌리면 각각 새 테스트 1건만 실패한다(payment 239건, notification
49건). `./gradlew test` 1255건 실패 0. e2e 단언 A4b·A12·B3 은 배선 때문에 그대로 둔다.

미충족: 없음.

## 실제 스택 e2e 와 JVM 테스트의 검증 책임 판정 (D-042, [#153](https://github.com/Kimgyuilli/PeekCart/pull/153), 2026-09-28)

ADR-0032 로 경계를 정했다. e2e 단언 하나는 서비스 간 배선과 도착 후 로직을 함께 싣는다. 배선은 실제 스택이,
로직은 JVM 이 맡는다. e2e 단언은 배선을 싣는 한 남기고, 로직이 e2e 에만 있으면 JVM 테스트를 더한다.
"JVM 이 이미 덮는다" 는 결함 주입으로만 인정한다. 이번 재평가로 제거한 e2e 단언과 대조군은 없다.

범위는 판정까지로 줄였다(사용자 결정, 등급 L 에서 M). 착수 전 코드 확인에서 TASKS 행의 "HTTP 생산자·소비자
호환성" 은 앱 서비스끼리 HTTP 호출이 없어 gateway 라우팅과 Toss 로 좁혀졌다. 이벤트 payload 타입은 `common`
공유라 같은 커밋의 필드 불일치는 컴파일이 막는다.

결함 주입 23건 중 20건을 지목한 JVM 테스트가 잡았고 3건은 공백이었다(PAY-005 코드, `payment.failed` 알림,
예약 실패 취소 알림). 배선이 없는 단언 7건은 모두 조회 1회라 빼도 시간이 줄지 않는다. 시간은 스택 기동에
있다(scenarios 잡에서 시나리오 82초, 기동 157초). 후속으로 D-047(JVM 공백 3건)과 D-048(스택 기동 시간)을 열었다.

착수 중 main 복구를 먼저 했다([#152](https://github.com/Kimgyuilli/PeekCart/pull/152)). `PaymentSecurityIntegrationTest`
가 공유 DB 를 비우지 않아 앞 클래스가 남긴 결제 행에 404 대신 200 을 받았고, #150·#151 머지 run 의 gate 를
연달아 떨어뜨렸다.

앞선 미충족 둘을 여기서 닫는다. D-041 의 V7: #151 머지 run
[36338959413](https://github.com/Kimgyuilli/PeekCart/actions/runs/36338959413) 의 음성 대조군 단계는 429초로,
직전 main run 의 658초에서 줄었다. D-045·D-046 의 전체 통과: 이 작업의 검증 실행에서 `./gradlew test` 가
한 번에 통과했다(1253 테스트, 실패 0).

미충족: 배포 버전 스큐는 e2e 와 JVM 모두 보지 않는다(ADR-0032 에 공백으로 기록). A0(단가 캐시 전제)와
D3(DLQ 식별자 non-null)는 주입하지 않아 JVM 중복으로 판정하지 않았다.

## e2e 음성 대조군의 실패 지점 특정과 결함 주입 경계 (D-041, [#151](https://github.com/Kimgyuilli/PeekCart/pull/151), 2026-09-28)

ADR-0031 로 결함 주입 경계를 정했다. 주입은 스택 바깥의 기존 수단(ShedLock 행, 컨테이너 정지, pg-stub
script, 입력 데이터)으로만 하고 서비스에 테스트 제어 표면을 두지 않는다. 대조군은 runner 안에서
`Timeout.stage` 로 실패 지점을 특정하고, 부재는 원인 관측 + 시스템 주기에서 유도한 창 + 주입 제거 후 복원
확인으로 증명한다. 대조군 목록은 `docs/06-testing-strategy.md` §13-1-b 이고 D-042 의 입력이다.

착수 전 D-033 CI 로그로 대조군 461초를 나눴다. 405초가 고정 대기였고, ② 는 시나리오 A 첫 줄에서 죽어
예약 단계에 닿지 않는 공허한 대조군이었다. 구현 중 복원 확인이 한 건을 더 잡았다. ① 이 ShedLock 행을
DELETE 로 해제해 poller 가 GC 전까지 락을 못 잡았고(ShedLock 6.3.1 `LockRecordRegistry`), 그 상태에서 ③ 이
예약도 취소도 없이 통과할 수 있었다. 해제를 `lock_until` UPDATE 로 바꾸고 ③ 에 예약 성공 관측을 더했다.

로컬 검증: 시나리오 4종 통과, 대조군 전량 통과(대조군 구간 305초), 무주입·엉뚱한 지점 주입 4종에서 대조군
FAIL, `--self-test` 14종. 대조군 6종은 유지한다.

미충족: CI 대조군 소요를 D-033 기준선 461초와 대조하는 것(V7)은 PR CI 실행 후 기록한다.

## 무응답 Redis 상한을 부하 잡음과 분리 (D-045·D-046, [#150](https://github.com/Kimgyuilli/PeekCart/pull/150), 2026-09-28)

D-044 의 미충족을 이어받아 product-service 공유 컨테이너 기동 대기 초과(D-045)를 재현하려 했다.
단독 `--rerun` 4회와 전체 `--rerun` 1회 모두 기동 시간이 정상 범위(MySQL 12~23초, Kafka 5~11초)라
재현 불가, 호스트 일시 지연 추정으로 닫았다. 코드 조치는 하지 않았다.

그 전체 실행에서 `ProductCacheFallbackIntegrationTest` V3 가 2.764s 로 상한 2.6s 를 넘었다(D-046).
로그상 타임아웃은 정확히 4회였고 초과분은 DB 조회 구간의 부하 잡음이다. 시간 상한은 timeout 소실·증가만
보도록 3.5s 로 두고, 캐시 경로 계약은 재고 캐시 get·put 증가분 단언으로 옮겼다. 곁에서 `FallbackSnapshot`
baseline 에 `productStock` 이 빠져 V1 재고 단언이 누적값이던 것을 고쳤다. 새 ADR 은 필요하지 않다.

미충족: 전체 `./gradlew test` 가 한 번에 통과한 실행이 여전히 없다. 수정 후에는 product-service 만 다시 돌렸다.

## 서비스 test 태스크의 무작위 시드를 입력에서 제외 (D-044, [#149](https://github.com/Kimgyuilli/PeekCart/pull/149), 2026-09-27)

서비스 5모듈의 클래스 순서 시드를 명시한 경우에만 test 태스크 입력으로 둔다. 무작위 시드는 `doFirst`
에서 넣어 입력 스냅샷에 잡히지 않는다. user-service 에서 변경 없는 재실행이 1m5s 에서 `UP-TO-DATE`
4s 가 됐다. `-PtestSeed`·`TEST_SEED` 재현 실행은 입력이 달라 반드시 돌고, 원 실행과 같은 클래스 순서를
낸다. 포기한 것은 변경 없는 재실행의 새 순서 1회뿐이고 필요하면 `--rerun` 을 쓴다.

착수 전 범위가 줄었다. CI 는 빌드 캐시를 쓰지 않아 매번 test 를 돌리므로 CI 동작 변화가 없고, TASKS 행이
요구한 CI 벽시계 측정은 필요 없었다. 새 ADR 은 필요하지 않다. Codex 리뷰는 호출하지 않았다(`.cache/codex-off`).

미충족: 전체 `./gradlew test` 가 한 번에 통과한 실행이 없다. 1회차에서 product-service 공유 컨테이너 하나가
기동 대기를 넘겨(`ContainerLaunchException`) 89건이 연쇄 실패했고 2회차는 통과했다. 시드와 무관하다고
판단했으나 재현하지 못했다.

## 로컬 검증 범위 재평가 — 전량 유지 (D-040, [#148](https://github.com/Kimgyuilli/PeekCart/pull/148), 2026-09-27)

ADR-0028 §후속 ④ 를 D-035 이후 실측으로 닫았다. 로컬 `./gradlew test --rerun` 이 706초, 8모듈
1253 테스트 0 실패다. 싱글톤 전환 전 서비스 5모듈 합계가 약 4510초였으므로 `work.md` §8 의 전량
실행을 좁힐 압력이 사라졌다. 규칙은 그대로 두었다.

착수 전 전제 하나가 틀렸다. 변경 없는 `./gradlew test` 는 입력이 같은 모듈을 건너뛸 것으로 봤으나
568초가 걸렸고 서비스 5모듈이 전부 돌았다. 클래스 순서 시드 기본값이 `System.currentTimeMillis()`
이고 test 태스크의 `systemProperty` 로 들어가 입력이 매 실행 바뀐다. 건너뛰는 것은 common · gateway ·
peekcart-common-auth 뿐이다. 시드를 입력에서 빼는 안은 D-035 의 매 실행 셔플 의도와 맞바꾸는 부분이
있어 D-044 로 등록했다. 새 ADR 은 필요하지 않다.

미충족: 서비스 test 태스크의 UP-TO-DATE 불가는 D-044 로 넘겼다. PHASE1·2 progress 는 엔트리가 `###`
헤딩이라 다이제스트가 읽지 못한다.

## product-service 통합 테스트 컨테이너 싱글톤 전환 (D-035 4/4, [#147](https://github.com/Kimgyuilli/PeekCart/pull/147), 2026-09-27)

product-service 통합 테스트 20클래스를 `@Import(SharedContainers.class)` 로 전환해 D-035 를 닫았다.
진입점의 무조건 `@EnableScheduling` 을 제거해 `SchedulingConfig` 게이트로 넘겼다. 브로커 왕복으로
리스너 소비를 관측하는 StockCompensationRefund · DeadLetterLedger 와 타이머 발화를 기다리는
StockSchedulerWiring 만 opt-in 하고 `@DirtiesContext(AFTER_CLASS)` 로 가뒀다(ADR-0029).

Redis 를 Toxiproxy 뒤에 두는 ProductCacheFallback 은 그대로 옮기면 깨진다. Boot 의 프로퍼티 기반
`RedisConnectionDetails` 는 `@ConditionalOnMissingBean` 이라, 공유 Redis 빈이 있으면 `spring.data.redis.*`
가 무시된다. 예외로 두지 않고 프록시를 가리키는 `@Primary` 빈으로 덮었다. Lettuce 와 Redisson 이
같은 빈을 받는다. 빼면 7개 중 5개가 red 다. EXPLAIN 테스트는 공유 DB 의 잔여 행 30개에서 풀스캔으로
뒤집히는 것을 확인하고 매 테스트 전에 DB 를 비우게 했다.

`:product-service:test --rerun` 이 1098초에서 156초가 됐다. 시드 3개 셔플 통과. D-035 ④ 토픽 누적은
최종 19개 전부 고정 이름, 메타데이터 타임아웃 0건으로 4모듈 모두 악화가 없었다. lint 이월 두 건도
닫았다. 대상을 test 소스가 있는 8모듈 전부로 넓히고, settings.gradle 과 대조한 누락 가드(ITC-005)와
FQN 매칭을 넣었다. `./gradlew test` 전량 통과. Codex 리뷰는 호출하지 않았다(`.cache/codex-off`).

미충족: `cleanDatabase` 는 `shedlock` 을 지우지 않으므로, 같은 락 메서드를 두 클래스가 직접 부르게
되면 뒤 클래스가 조용히 skip 된다. 지금은 호출자가 각 한 곳이다. Redis keyspace 전체 정리 헬퍼는
만들지 않았다. 새 ADR 은 필요하지 않다.

## payment-service 통합 테스트 컨테이너 싱글톤 전환 (D-035 3/4, [#146](https://github.com/Kimgyuilli/PeekCart/pull/146), 2026-09-26)

payment-service 통합 테스트 13클래스를 `@Import(SharedContainers.class)` 로 전환했다. 진입점의
무조건 `@EnableScheduling` 을 제거해 `SchedulingConfig` 게이트로 넘겼다. 브로커 왕복으로 리스너
소비를 관측하는 5클래스와 타이머 발화를 기다리는 ShedLock 만 opt-in 하고 `@DirtiesContext(AFTER_CLASS)`
로 가뒀다(ADR-0029).

payment 는 테스트 클래스끼리 고정 토픽을 주고받는 첫 모듈이었다. DeadLetterLedger 가 `*.dlq` 에
보낸 레코드가 Dlq 테스트 리스너(earliest)에 `@BeforeEach` 의 `clear()` 뒤에 도착하는 것을 관측했다.
리스너는 context 기동 시점부터 읽으므로 `cleanKafkaTopics` 로는 순서를 보장할 수 없다. 그래서 같은
클래스 세 번째 케이스가 쓰던 고유 key 필터를 앞 두 케이스에 맞췄다. 수정 전 클래스와 메서드 순서를
강제하면 red, 수정 후 green 이다. 클래스 순서만 강제한 첫 재현은 green 이었다. 메서드 순서까지
고정해야 누수가 드러난다는 점은 계획서 정정 이력에 남겼다.

`:payment-service:test --rerun` 이 1919초에서 55초가 됐다. 시드 3개 셔플 통과. opt-in 6개를 끄면
전부 red 가 된다. D-035 ④ 토픽 누적은 최종 20개 전부 고정 이름이고 메타데이터 타임아웃 0건이었다.
`./gradlew test` 전량 통과. Codex 리뷰는 호출하지 않았다(`.cache/codex-off`).

미충족: lint 가 FQN 표기(`@org.testcontainers.junit.jupiter.Testcontainers`)를 잡지 못하는 기존
사각지대를 검증 중 발견했다. 목록 누락 가드와 함께 product PR 에서 판단한다. 새 ADR 은 필요하지 않다.

## notification-service 통합 테스트 컨테이너 싱글톤 전환 (D-035 2/4, [#145](https://github.com/Kimgyuilli/PeekCart/pull/145), 2026-09-26)

notification-service 통합 테스트 7클래스를 `@Import(SharedContainers.class)` 로 전환했다. 자율
writer 를 가진 첫 확산 대상이라 진입점의 무조건 `@EnableScheduling` 을 제거해 `SchedulingConfig`
게이트로 넘겼고, 리스너 소비를 브로커 왕복으로 관측하는 Consumer · DeadLetterLedger 두 클래스만
리스너를 켜고 `@DirtiesContext(AFTER_CLASS)` 로 가뒀다(ADR-0029). 스케줄러 opt-in 은 0건이다.

`:notification-service:test --rerun` 이 342초에서 69초가 됐다. 시드 3개 셔플 통과. opt-in 을
끄면 두 클래스가 red 가 되는 것으로 조용한 green 이 아님을 확인했다. D-035 ④ 토픽 누적은 최종
12개 전부 고정 이름이고 메타데이터 타임아웃 0건이었다. `./gradlew test` 전량 통과. Codex 리뷰는
호출하지 않았다(`.cache/codex-off`).

lint self-test 픽스처가 `MODULES` 를 순회하도록 바꿔 대상 확대마다 픽스처를 고치던 문제를 없앴다.

미충족: 그 대가로 목록에서 빠진 모듈을 self-test 가 잡지 않는다(product PR 에서 판단). 토픽
누적은 notification 단독 관측이라 payment · product 에서 다시 본다. 새 ADR 은 필요하지 않다.

## user-service 통합 테스트 컨테이너 싱글톤 전환 (D-035 1/4, [#144](https://github.com/Kimgyuilli/PeekCart/pull/144), 2026-09-26)

D-032 가 order-service 에서 검증한 컨테이너 모듈 싱글톤을 user-service 로 넓혔다. 통합
테스트 4클래스를 `@Import(SharedContainers.class)` 로 전환하고, test 태스크에 스케줄러·리스너
기본 off(ADR-0029)와 클래스 순서 랜덤·시드 출력을 넣었으며, `integration-test-container-lint.sh`
검사 대상에 user-service 를 추가했다(self-test 7/7).

`:user-service:test --rerun` 이 176초에서 58초가 됐다. 시드 3개 셔플 통과, cleanup 을 자기
행만 지우도록 바꾼 뮤테이션에서 해당 클래스가 앞선 시드만 통과해 셔플의 검출력을 확인했다.
`./gradlew test` 8모듈 1253 테스트 통과. Codex 리뷰는 호출하지 않았다(`.cache/codex-off`).

미충족: user 는 writer 0 이라 ③ opt-in 조사는 0건이고, Kafka 미사용이라 ④ 토픽 누적 확인은
notification·payment·product PR 로 이월했다. user 에서도 Kafka 컨테이너가 한 번 뜨며 58초에
포함된다. 새 ADR 은 필요하지 않다.

## CI 최종 게이트와 이미지 승격 경계 (D-043, [#142](https://github.com/Kimgyuilli/PeekCart/pull/142), 2026-09-26)

ADR-0030 에 따라 `gate` 가 lint/test/guards/images/e2e 의 실패·skip 을 최종 집계하고,
main push 의 `publish` 는 성공한 gate 뒤에만 시작하도록 연결했다. 이미지 artifact 는
checksum·이미지 ID 를 기록하며 e2e 와 publish 가 로드 후 ID 를 확인한다. 게시 단계는
SHA 태그의 원격 config digest 를 검증하고 같은 manifest digest 로 `latest` 를 승격한 뒤
원격 태그를 재조회한다. 브랜치 보호의 `enforce_admins=true` 를 적용·재조회했다.

로컬 `./gradlew test --no-daemon` 통과(30분 8초, 49 tasks executed), 게이트 배선
변형 검사 24/24와 관련 lint 가 통과했다. PR CI [run 36169884411](https://github.com/Kimgyuilli/PeekCart/actions/runs/36169884411)에서
두 e2e 모드와 모든 선행 job 이 성공했고, 대조군 완료 뒤 gate 가 시작해 성공했다.
PR 의 publish 는 건너뛰었다. 이후 [main push run 36228604631](https://github.com/Kimgyuilli/PeekCart/actions/runs/36228604631)에서
시나리오 08:11:26 UTC·음성 대조군 08:17:28 UTC 완료 뒤 gate 가 08:17:31 UTC 시작해
08:17:45 UTC 성공했고, 6개 publish 는 모두 그 뒤에 시작해 성공했다. GHCR 원격에서
커밋 SHA 태그와 `latest` 의 manifest digest 가 6개 이미지 모두 일치함을 재조회했다.
브랜치 보호의 필수 체크는 `gate`(GitHub Actions 앱 ID `15368`), `strict=true`,
`enforce_admins=true` 로 재확인했다. D-043 완료(2026-09-26, see ADR-0030).

## main 필수 체크 복구 (D-037, [#141](https://github.com/Kimgyuilli/PeekCart/pull/141), 2026-09-25)

브랜치 보호가 존재하지 않는 `build` 체크를 요구하던 상태를 확인하고, 실제 CI 의 `gate`
체크로 교체했다. PR #140 head 에서 `gate` 를 발행한 GitHub Actions 앱 ID `15368` 을 확인한
뒤 같은 앱으로 고정했다. `strict=true` 와 다른 보호 설정은 유지했다.

원본 Git 이력 기준 `build` job 은 #97(2026-09-01 09:53:05Z)에서 main 에서 제거됐다. main 의
first-parent 이력에서 #98~#140 사이 실제 병합된 PR 은 40개다. #101은 PR 레코드가 없고 #113은
`feat/user-jwt-public-key-seam` 에 병합됐다. #114는 #113을 main 에 재적용한 PR이며, #130은 종료됐지만
병합되지 않았다. #141까지 포함하면 main 병합은 41개이고 그 PR에서 필수 체크를 `build`에서 `gate`로
복구했다. 따라서 #135~#137도 존재하지 않는 `build` 체크가 필수였던 기간에 main 으로 병합됐다는 점은
이력으로 확인된다.

GitHub API 재조회, 게이트 실패 전파 lint 자체 검사 6/6, `./gradlew test` 가 통과했다.
별도 Codex 리뷰는 호출하지 않았다. 실제 실패 PR 의 병합 UI 는 재현하지 않았다.
관리자 우회(`enforce_admins=false`)와 `gate` 밖의 lint·이미지·e2e 검증 범위는
D-043 에서 다룬다. 새 ADR 은 필요하지 않다.

## e2e 시나리오와 음성 대조군 병렬 실행 (D-033, [#140](https://github.com/Kimgyuilli/PeekCart/pull/140), 2026-09-25)

D-032 CI 실측에서 e2e 16.1분이 단독 병목으로 확인돼 ADR-0028 §후속 ①을 재판정했다.
PR에서 음성 대조군을 계속 실행하면서 직렬 대기를 없애기 위해 `e2e`를 `scenarios`와
`negative-control` 매트릭스로 분리했다. 두 실행은 별도 러너와 cold start 스택을 쓰고,
각자 run ID·증적 artifact 이름을 가진다. 시나리오 증적 게이트는 시나리오 잡에 남겼다.

워크플로 배선 lint는 모드 누락, 이미지 의존·증적 게이트 삭제, 중복 artifact 이름 등을
변이 7종으로 검출한다. 로컬 `./gradlew test` 전량 통과(27분 54초). 별도 Codex 리뷰는
호출하지 않았다.

PR CI [run 36075940872](https://github.com/Kimgyuilli/PeekCart/actions/runs/36075940872)은
전체 성공했다. 두 e2e 잡이 00:08:58 UTC에 동시 시작해 시나리오 5분 13초,
음성 대조군 10분 54초에 성공했다. 전체 14분 10초로 D-032 PR 실측 20분 49초보다
6분 39초 짧았다(단일 run 비교). product-service 테스트 13분 44초와 대조군이
거의 함께 끝나 새 임계경로를 이룬다.
별도 러너의 이미지 로드와 스택 기동이 중복되므로 러너 사용 시간은 늘 수 있다.
`publish`가 e2e를 기다리지 않는 기존 계약은 이번 변경에서 유지했다.

## 통합 테스트 컨테이너 모듈 싱글톤 전환 (D-032, [#138](https://github.com/Kimgyuilli/PeakCart/pull/138), 2026-09-24)

> 2026-10-17 D-080 정정: 아래 844초 전부를 기동 비용으로 귀속한 서술은 철회한다.
> 같은 CI 자료에는 마지막 클래스 뒤 약 515초가 있으며, 로컬 재현의 종료 꼬리는 479.5초다.
> [원본 재집계](../evidence/D-080/README.md) 참조. 원본 순차 run1/run3/run5에서도
> 결함 4(preconditionUsesRealOrderAdapter)가 실패했으므로 셔플의 최초 발견이라는 설명도 정정한다.
> 다른 세 결함의 최초 발견 시점은 이 자료로 판단하지 않는다.

ADR-0028 의 결정을 order-service 에 구현했다. `:order-service:test` 975초 중 844초(87%)가
컨테이너 부팅·Flyway·context 기동이었고, 그것을 모듈 싱글톤으로 합쳤다. 로컬 221~277초.

**착수 후 범위가 늘었다.** 계획서의 "Kafka 위험 없음" 판정이 오판이었고(테스트 헬퍼의 UUID
토픽만 보고 애플리케이션 고정 토픽을 놓쳤다), 그 아래에 **자율 writer 가 테스트에서 기본
on** 이라는 근본 원인이 있었다. 스케줄러와 `@KafkaListener` 둘 다였고, 프로덕션 코드를
건드리는 결정이라 ADR-0029 로 먼저 고정한 뒤 적용했다 (see ADR-0029).

**셔플(V-4)이 결함 4건을 드러냈다.** 순차 실행 420건 전건 통과는 순서가 운 좋았던 것이다.
그중 리스너 결함은 기존 워크어라운드가 무효라는 것까지 파고들어야 했다 — `groupId` 가
상수라 캐시된 다른 context 의 consumer 가 파티션을 넘겨받는다. 컨테이너를 빈으로 노출하면
context 파괴 시 새 포트로 재기동돼 캐시된 다른 context 가 전멸하는 것도 여기서 나왔고,
그 때문에 **ADR-0028 §Decision 의 메커니즘 서술이 사실과 달라져** `fix(adr):` 로 정정했다.

**V-7 CI 실측**: `:order-service:test` 가 975초에서 **224초**로 줄었다(4.35배). 잡 벽시계는
18.6분에서 5.2분이다. run5 이상치(7018초/27건)는 CI 에서 재현되지 않아 로컬 자원 고갈
가설이 남는다.

**그런데 CI 전체 벽시계는 줄지 않았다** — PR #138 run [35753591828](https://github.com/Kimgyuilli/PeakCart/actions/runs/35753591828)은
19분 03초, 다음 비교 run 은 20분 49초였다. 첫 run 에서 `test(order)` 는 16:40:20Z 에 끝났고,
`gate` 는 16:40:24Z–16:40:41Z 에 실행됐다. `e2e` 는 16:40:35Z 에 끝나 gate 보다 6초 빨랐다.
run 의 실제 workflow revision(`703fe73`)에서 `gate` 는 `[test, guards]`, `e2e` 는 `images` 에
의존했다. 따라서 이 run 에서는 `test(order) → historical gate` 경로가 독립 `e2e` 경로보다
6초 늦게 끝났고 두 경로는 거의 동률이었다. 이는 당시 DAG 의 측정이며, 현재 `gate` 가 모든
필수 job 을 기다리는 계약을 뜻하지 않는다. 따라서 이 비교에서 test 가 이미 임계경로 밖이었다고
할 수 없다.

또한 PR #138 run [35976903613](https://github.com/Kimgyuilli/PeekCart/actions/runs/35976903613)의
order-service 이미지 로그는 GHA cache manifest 를 가져온 뒤 11개 레이어를 `CACHED` 로 표시한다.
그 뒤 `COPY order-service/build.gradle` 다음 dependency 단계가 28.8초, `bootJar` 가 약 34초
걸렸다. PR #138 은 이 Gradle 파일에 27줄을 추가해 해당 단계와 뒤 레이어를 무효화했다. 관측된
증거는 완전한 캐시 부재가 아니라 해당 입력 변경으로 인한 부분 재빌드를 뒷받침한다. 이 전환의
값어치는 CI 벽시계가 아니라 **로컬 반복 비용과 확장성**이라는 판단은 유지한다.

부수로 `e2e` 16.1분이 단독 병목이라는 **D-033 의 전제가 실측으로 확증**됐다.

V-6 은 기대에 못 미쳤다. 25개 context 가 15회 기동하는데, `@DirtiesContext` 를 붙인
4클래스가 재생성을 강제하기 때문이다. **격리를 사서 캐시 적중을 일부 내준 것**이고
ADR-0029 §Consequences 의 트레이드오프가 수치로 나타났다. 확산(D-035)에서 opt-in 이 늘면
이 수가 이득을 깎는다.

## 테스트 자율 writer 정책 확정 (D-036, ADR-0029, [#138](https://github.com/Kimgyuilli/PeakCart/pull/138), 2026-09-24)

D-032 의 V-4 blocker 를 풀기 위한 선행 결정이다. 구현(`SchedulingConfig` 신설,
`OrderApplication` 의 `@EnableScheduling` 제거)이 결정보다 앞서 있던 상태를 되돌렸다 —
ADR-0028 은 *컨테이너 수명* 결정이지 *자율 writer 정책* 이 아니다.

**진단이 계획서보다 한 단계 깊었다.** 계획서 §2-3c 는 결함 4를 "`@KafkaListener` 가 자율
writer 다" 까지 적었으나, 그 테스트에는 **이미 워크어라운드가 있었다**(`@BeforeEach` 에서
자기 context 의 리스너 컨테이너를 `stop()`, 2026-09-11 `89955c1`). 그런데도 실패하는 이유를
실측으로 갈랐다 — `@KafkaListener` 의 `groupId` 가 하드코딩 상수라 **모든 캐시된 context 가
같은 그룹으로 같은 브로커에 붙는다.** 세 클래스만 돌린 런에서 `order-svc-stock-result-group`
에 서로 다른 context 의 consumer 2개(`-36`, `-44`)가 공존하고 `generation 4` 까지 리밸런스하며
파티션이 넘어가는 것을 확인했다. **per-context 수단으로는 구조적으로 막을 수 없다** 는 것이
이 ADR 이 필요했던 이유다.

`spring.kafka.listener.auto-startup` 이 듣지 않는 이유도 함께 확인했다 — 5개 서비스 전부
`ConcurrentKafkaListenerContainerFactory` 를 손수 `@Bean` 으로 만들어 Boot 의 auto-configured
factory 를 쓰지 않는다.

**결정(ADR-0029)**: 테스트에서 자율 writer(스케줄러 + Kafka 리스너)는 기본 off, 그 동작을
검증하는 테스트만 opt-in, 켠 테스트는 `@DirtiesContext(AFTER_CLASS)` 로 수명을 자기 클래스에
가둔다. 리스너 게이트는 Boot 속성이 아니라 factory 의 `autoStartup` 에 건다. 대안 5종
(현행 per-context stop · Boot 속성 · `groupId` 랜덤화 · 테스트 전용 프로파일 · `@MockBean`)의
기각 사유를 함께 남겼다.

**적용은 이 항목 밖이다** — order-service 는 D-032, 나머지 4모듈은 D-035 에서 한다.

## CI 그래프 직렬화 해소 + 컨테이너 수명 결정 ([#135](https://github.com/Kimgyuilli/PeakCart/pull/135), 2026-09-23)

CI 36분의 구조를 측정으로 분해하고, 그 결과를 ADR-0028 로 고정한 뒤 1단계를 구현했다.

측정이 먼저였다. 실측 [run 35731821466](https://github.com/Kimgyuilli/PeakCart/actions/runs/35731821466)
에서 `test(order) 1051s` · `gate 15s` · `images 173s` · `e2e 856s` 의 네 job duration 합은
2095초(34분 55초)였다. 직렬 구간의 벽시계는 13:12:23Z 부터 e2e 종료 13:47:57Z 까지
35분 34초다. 두 값은 job 내부 시간의 합과 job 사이 대기까지 포함한 경과 시간으로 구분한다.
샤드 내부는 더 극단적이었다 — `:order-service:test` 975초 중 JUnit XML 이
보고하는 클래스 시간은 **131초뿐**이고 나머지 **844초(87%)** 가 컨테이너 부팅·Flyway·context
기동이다. JUnit 이 static initializer 를 testcase 시간에 넣지 않아 그동안 보이지 않던 구간이다.

**결정(ADR-0028)**: 컨테이너 수명을 per-class 에서 모듈 싱글톤으로 바꾼다. 싱글톤 전환 ·
`@Container` 금지 lint · 순서 셔플 검증을 한 묶음으로 간다. 대안 6종(JUnit 병렬 · Redpanda ·
withReuse · 러너 코어 재배분 · e2e PR 제외 · 모듈 분할)의 기각 사유를 함께 남겼다.
워크플로 그래프 변경은 YAML 한 줄이고 되돌림이 즉시라 ADR 보호 대상에서 제외했다.

**구현(D-031, 1단계)**: `images` 의 needs 를 `[lint]` 로 완화해 test 와 병렬화했다.

착수 후 범위가 늘었다. `publish` 가 `needs: images` 하나만 걸고 있어서 그동안 `images` 를 거쳐
**간접적으로만** 게이트되고 있었고, 그 경로를 끊으면 테스트가 실패한 main push 에서도 GHCR 에
`:latest` 가 올라간다. `publish: needs: [images, gate]` 로 직결하고, 그 연결이 다시 끊기지
않도록 `scripts/ci-release-gate-lint.sh` 를 신설했다. 도달성만 보지 않고 `gate` 의 실패 전파
스텝 존재까지 본다 — `gate` 는 `if: !cancelled()` 라 선행이 실패해도 초록으로 끝날 수 있어서다.
이 발견으로 등급이 S 에서 M 으로 올라갔다.

`images` 빌드를 buildx + `type=gha` 캐시로 전환했다. scope 를 서비스별로 나눈다(매트릭스 6개가
단일 scope 를 공유하면 서로의 캐시를 덮어쓴다). `load: true` 가 필수인데 `docker-container`
드라이버가 결과를 로컬 daemon 에 남기지 않아 뒤따르는 health smoke 와 `docker save` 가 이미지를
찾지 못하기 때문이고, 로컬에서 양방향으로 확인했다.

**실측 (머지 후)**:

| | 베이스라인 | 변경 후 | 차이 |
|---|---|---|---|
| PR run | 36분51초 | **21분51초** | -15분 (-40.7%) |
| main push run | 39분18초 | **21분20초** | -18분 (-45.7%) |

`images` 가 15:04:50 에 시작하고 `test(order)` 가 15:20:29 에 끝났다. **15분39초 앞서 시작**해
직렬 사슬이 실제로 끊겼다. 다만 목표였던 20분에는 1분51초 미달이다.

**릴리스 게이트가 실작동했다.** main push run 에서 `gate` 종료(15:49:38) 후 `publish` 6개가
15:49:42 에 시작했다. PR 에서는 `publish` 가 skip 되므로 이 확인은 머지 후에만 가능했다.
`ci-release-gate-lint` 도 CI 에서 `self-test OK (6/6)` 로 조작 입력 4종을 전부 탐지했다.

**임계경로가 이동했다.** `test(order) 1051s -> gate -> images 173s -> e2e 856s` 였던 것이
`lint 51s -> images matrix barrier 242s -> e2e 949s` 가 됐다. D-031 PR run
[35744585147](https://github.com/Kimgyuilli/PeakCart/actions/runs/35744585147)에서 206초는
order-service 이미지 job 이고, e2e 시작 직전 완료된 matrix 최장 job 은 notification-service
242초였다. 20분 미달의 주된 직렬 구간은 여전히 e2e 였으며, 이는 ADR-0028 §후속 ① 이 예측한
상태이자 D-033 의 표적이다.

**미충족**:

- ~~buildx `type=gha` 캐시가 순손실이다~~ → **D-034 에서 유지로 판정(2026-09-22).** 다음
  main push run [35751377973](https://github.com/Kimgyuilli/PeakCart/actions/runs/35751377973)
  에서 `CACHED` **30스텝**, `images` **86~130초**로 베이스라인(104~173초)보다도 빨랐다.
  첫 run 이 느렸던 원인은 GitHub Actions 캐시의 ref 격리였다 — PR 브랜치가 채운 캐시를
  main push 가 읽지 못해 콜드였고 `cache-to mode=max` 의 export 비용만 냈다
- 로컬 `--load` 재빌드가 6.25초에 CACHED 29스텝이었던 것은 BuildKit 로컬 캐시였고,
  `type=gha` 의 거동을 예측하지 못했다
- `publish` 가 `e2e` 를 기다리지 않는 것은 **기존 상태**이고 범위 밖으로 두었다. D-033 에서
  e2e 실행 정책을 정할 때 함께 본다

**후속**: D-032(싱글톤 본체, 844초가 표적) · D-033(e2e 음성 대조군 605초 정책 재판정).
D-034 는 같은 날 판정 완료.
Codex 리뷰는 계획·diff 양쪽 모두 `.cache/codex-off` 로 차단된 상태에서 진행했다(의도적 생략).

## Phase 5 기반 세팅 — 로드맵 축 제거 ([#134](https://github.com/Kimgyuilli/PeakCart/pull/134), 2026-09-22)

Phase 4 가 종결([#133](https://github.com/Kimgyuilli/PeakCart/pull/133))되면서 사전 로드맵이
소진됐다. Phase 5 는 순서표를 다시 만들지 않기로 했으므로, **문서가 순서표를 전제하던
자리들을 먼저 걷어냈다.** 코드 변경 0.

무엇을 했나:

- `docs/TASKS.md` — `## 현재 단계` 를 Phase 5 로 교체. 그 자리를 차지하던 Phase 4
  설계(A1~A4.5)·구현(①~⑥) 표는 **아래 `## Phase 4 — MSA 분리` 섹션으로 합쳤다**
  (두 곳에 나뉘어 있던 Phase 4 기술이 한 곳이 됐다)
- 부채표 제목을 `개발 부채 / 작업 (Tech Debt & Backlog)` 로. **표를 나누지 않는 것이
  결정이다** — 수요 기반에서 "부채 해소"와 "신규 작업"은 같은 성격이고, 표가 둘이면
  항목 추가 때마다 분류부터 해야 한다. 다음 번호는 D-031
- `docs/progress/PHASE5.md` 신설 — `harness-context.sh` 가 `현재 단계` 줄에서 Phase
  번호를 추론해 `PHASE{N}.md` 를 읽으므로, 이 파일이 없으면 다이제스트가 파서 고장으로 떨어진다
- `docs/07-roadmap-portfolio.md §16` — Phase 5 항목을 추가하되 **작업 목록도 Exit
  Criteria 도 적지 않았다.** 적으면 그게 로드맵이 되기 때문이다. 현황은 TASKS.md 를 가리킨다

**검증**: `scripts/harness-context.sh --check` = `ok` (단계가 Phase 5 로 읽히고 PHASE5.md 를
찾는다) · `scripts/plans-index.sh --check` exit 0.

**부수 정리**: `task-phase4-closure` 계획서가 PR 링크 부재로 `보류` 였다. #133 이 근거임을
확인해 계획서에 링크를 적고 `done/` 으로 아카이브했다(인덱스 51행).

**미충족**: 없다. Phase 5 는 종결 조건이 없는 단계이므로 "남은 항목" 개념이 이 작업에 없다.

**다음**: D-030 (Slack 채널 분리 + DLQ 적재량 메트릭) — 5서비스 분리로 per-service
태그(ADR-0015)가 갖춰져 라우팅 기준이 이미 있다.

### 후속 PR 기록 (2026-10-17)

[#176](https://github.com/Kimgyuilli/PeekCart/pull/176)에 D-060 후속 보강과 D-074·D-079·D-081·D-082를 포함했다. 승인 보호 운영 복구의 남은 범위는 D-084로 추적한다. 기존 사용자 TASKS 편집 중 미완료 부채 D-072·D-073·D-075·D-076·D-077·D-078·D-080·D-083 등록도 포함했다. D-059 계획서는 #174 머지에 따라 done 디렉터리로 이동하고 인덱스를 재생성했다. 새로 등록한 부채 8건의 구현은 미완료 상태로 유지한다. PR은 생성했으며 머지는 하지 않았다.


## 2026-10-21 — D-098 독립 검토·병합 후 확인 완료

[PR203](https://github.com/Kimgyuilli/PeekCart/pull/203) 최종 head `2e7251b6b855009ce14adccd0c71a46d23a5ddf8`의 [독립 재검토](https://github.com/Kimgyuilli/PeekCart/pull/203#issuecomment-6312472715)는 세 P2 반례 해소, 직접 52개 fixture 및 실제 원문 분리·archive 정상/음성 대조를 확인했다. 수정 제거 변이 세 개도 각각 실패했다. [PR CI39575261544](https://github.com/Kimgyuilli/PeekCart/actions/runs/39575261544)가 성공한 뒤 정상 병합 SHA는 `f4ee03b97b0bec0c9be7cd3077911fb67b578b74`다. [main CI39578683569](https://github.com/Kimgyuilli/PeekCart/actions/runs/39578683569)는 E2E·gate와 여섯 publish 모두 성공했다. 운영 배포와 실제 계획 archive는 수행하지 않았다.

TASKS 완료 행은 상태 외 본문과 비교 base의 순서를 보존해 이동한다. 일반 문서의 사실 정확성·전체 링크 건강이나 D-099 현행 corpus 검증을 완료했다고 주장하지 않는다. 이 완료 기록 자체의 PR 검증·병합은 캠페인 기록 작업에 남아 있다.


## 2026-10-21 — D-075 무료 분석 부분 병합 확인

[PR200](https://github.com/Kimgyuilli/PeekCart/pull/200) 최종 head `3d960601f97dcfac58c00640d2bc00bbdfc2a7e3`는 300개 fixture의 유한 drain, DB save·publish timer·lock skip 및 DB 없는 producer 대조를 보존한다. [최신 독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/200#issuecomment-6313108582)는 원측정 소스·raw·hash 보존과 main 통합 영향, 실제 Actions 보존 gate를 확인했다. 이전 직접 53개 marker 및 여섯 추출기 반례 검토는 변경 없는 자료의 근거로 재사용했고 새 성능 실측으로 주장하지 않았다.

[PR CI39579299737](https://github.com/Kimgyuilli/PeekCart/actions/runs/39579299737) 성공 후 병합 SHA는 `bdc370a80e0bfa6adefb6a436a5f608f878d1b69`다. [main CI39581607992](https://github.com/Kimgyuilli/PeekCart/actions/runs/39581607992)는 E2E·gate와 여섯 publish 모두 성공했다. 운영 배포·설정 변경은 없었다.

D-075는 부분 완료로 남는다. P1 목표 TPS/backlog/초과 대응, P3 정책 선택, P4 선택 정책 검증은 사용자 결정과 후속 근거가 필요하다. 유한 로컬 drain을 지속 TPS나 GKE 천장으로 환산하지 않는다. D-078의 무료 jitter/DLQ 및 실제 scheduler pool4 검증은 별도 후속이며 이 분석으로 완료되지 않았다.


## 2026-10-21 — DLQ fixture·진단 변경 병합 후 확인

[PR205](https://github.com/Kimgyuilli/PeekCart/pull/205) 최종 head `b8cde5d722391a3d42366db15018c17dd9d84230`의 [독립 통합 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/205#issuecomment-6313390929)는 기존 코드·음성 대조 증거의 보존과 고정 seed Actions의 실제 54 suites/334 PASS 및 cleanup을 확인했다. 원20초 단언과 최대5초 best-effort 진단 경계는 유지했다.

[일반 CI39581901199](https://github.com/Kimgyuilli/PeekCart/actions/runs/39581901199)와 [고정 seed CI39581901183](https://github.com/Kimgyuilli/PeekCart/actions/runs/39581901183)가 성공한 뒤 정상 병합 SHA는 `d506f7eef5d29e9894342a6d47b93b89a34266b2`다. [main CI39584187702](https://github.com/Kimgyuilli/PeekCart/actions/runs/39584187702)는 E2E·gate와 여섯 publish 모두 성공했다.

오래된 timestamp fixture의 retention 삭제는 독립 실험으로 확인했지만 PR194의 과거 CI 실패 원인은 당시 broker 증거가 없어 미확정이다. 이 변경은 D-065의 운영 표본·실제 GKE·NOT NULL 적용·replay 개방이나 D-078의 별도 실패 분석을 완료하지 않는다. 운영 배포와 실제 replay는 수행하지 않았다.

## 2026-10-21 — D-099 독립 검토·병합 후 확인 완료

PR [#204](https://github.com/Kimgyuilli/PeekCart/pull/204) 최종 head `48fca6eeaa6e65ba16f6e1cf75bc7dc473865c70`, merge `43a1745a548b24eb85a88ba3e5ecd77bc0742326`. 독립 [최종 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/204#issuecomment-6313836584)에서 세 P2 수정과 최신 main 통합을 확인했다. Actions의 실제 41 methods/16 mutants, 역사 덮어쓰기 거부·복구 허용, 정확 YAML 조회/fetch 실패 전파를 검토했다. PR CI39584448650과 실제 main CI39586582112/여섯 publish 모두 SUCCESS를 Root가 조회했다.

명시 base의 ID 감소와 현재 빈/중복/손상 archive를 검사한다. standalone만으로 겉보기 정상인 감소를 알아내거나, 부채 전체 완료의 의미를 검증한다는 뜻은 아니다. TASKS 행은 원본문·순서를 보존해 이동했고 이 기록 자체의 PR 검증·병합은 캠페인 기록 작업에 남아 있다.

## 2026-10-21 — D-103 독립 검토·병합 후 확인 완료

PR [#206](https://github.com/Kimgyuilli/PeekCart/pull/206) 최종 head `6b99cb90adf0dda127297da8e30f044e86756601`, merge `3bb01bbc3190a9aa38a7b4e6faec7bb767117808`. 독립 [최신 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/206#issuecomment-6314103635)는 실제 숫자 반례·정확 oracle·통합 delta와 Actions 9 methods/10 mutants를 확인했다. wrapper 45 PASS/원자료 cache 1 skip은 전체 역사 raw 검증으로 확대하지 않는다.

PR CI39586719840 attempt1의 payment bootJar 의존성 해석 실패는 테스트 시작 전 발생했고 원로그를 보존했다. 동일 12 URL의 이후 HTTP200·의존성 설정 불변을 확인한 뒤 source 변경 없이 실패 잡만 재실행했다. attempt2에서 실제 payment 54 suites/334 tests/실패·오류·skip0 및 전체 gate SUCCESS를 확인했다. 당시 원인을 나중의 가용성으로 단정하지 않는다. [재실행·병합 근거](https://github.com/Kimgyuilli/PeekCart/pull/206#issuecomment-6314417217). 실제 main CI39589440384와 여섯 publish 모두 SUCCESS를 Root가 조회했다.

역사 계약의 선택 필드 생략 호환을 유지하고 필수 metadata/items 내부 계약과 정확 정수 판정을 검증한다. D104의 모집단 정정은 별도 작업이다. TASKS 행은 상태 외 본문·순서를 보존해 이동했다. 이 완료 기록 자체의 최종 PR 검증·병합은 캠페인 기록 작업에 남아 있다.

## 2026-10-21 — signed 경로 권한 예외 수정 병합 후 확인

[PR211](https://github.com/Kimgyuilli/PeekCart/pull/211) 최종 head `3cf31328b3490b0885be68b5491e3534f259a6ed`, merge `89797c9639e15fb7c362e6be49687e652f80d3b9`. 공통 MVC 예외 처리에서 AccessDeniedException을 기존 보안 필터로 전달해 USER403·무인증401을 보존하고 다른 예외500은 유지한다. 실제 기존 결함 음성은 3개 중 2실패였고 수정 뒤 실제 313 platform tests가 통과했다. [독립 최종 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/211#issuecomment-6314585933)는 정확한 SHA와 원 XML·변경 영향을 검토했다.

PR CI39589619261 성공 및 실제 [main CI39592220364](https://github.com/Kimgyuilli/PeekCart/actions/runs/39592220364)의 모든 테스트·E2E·gate·여섯 publish SUCCESS를 Root가 확인했다. D-065의 실제 signed 전체 체인은 별도 PR210이며 운영 replay는 비개방 상태다. 운영 표본·GKE·NOT NULL 적용 등 남은 조건을 완료로 바꾸지 않는다.

## 2026-10-21 — D-065 signed gateway 부분 범위 병합 후 확인

최종 head `0be5aa32ec9159cdbc86f351475443fa090aec7f`의 [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/210#issuecomment-6315032985)는 새 전용 Actions39592715075의 실제64HTTP/20DB snapshot/112files와 네 fixture 정리를 확인했다. 일반 CI39592715276도 성공했다. [PR210](https://github.com/Kimgyuilli/PeekCart/pull/210)을 보호 우회 없이 직렬 병합한 실제 main은 `7894dcc9cf0de3dd966f62777d1b463a1d9313dc`다. [main CI39595168219](https://github.com/Kimgyuilli/PeekCart/actions/runs/39595168219)의 테스트·E2E·gate와 여섯 publish 모두 SUCCESS를 Root가 직접 조회했다.

이 기록은 무료 signed gateway 경로 검증의 완료다. 운영 replay는 계속 false이며 D-065 전체는 부분 완료다. 실제 운영 표본·GKE·NOT NULL 적용, 별도 수집기/제약/Flyway PR의 최신 병합 검증을 대신하지 않는다. 이미지 archive의 독립 로컬 재다운로드 검증은 수행하지 않았고 Actions checksum/loadID 검사와 구분한다.

## 2026-10-21 — D-087 실제 계측 병합과 후속 CI 회귀

[PR209](https://github.com/Kimgyuilli/PeekCart/pull/209) head `20532aca4ec3b4ccbd1f314bc65e9ef2e064df97`, merge `c9e12d695e21c348a82da78c6577eac7b2dd4d89`. 독립 [최신 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/209#issuecomment-6315314079)는 실제6서비스 profile raw·해석과 최신main 실행 delta를 검토했다. PR CI39595421179 성공, 실제 [main CI39605505984](https://github.com/Kimgyuilli/PeekCart/actions/runs/39605505984)는 23잡 전부 성공했고 여섯 publish도 성공했다. 단일 실행의 profile 숫자를 성능 개선이나 새main 천장으로 확장하지 않는다.

이후 PR189/214의 일반 CI에서 여섯 이미지가 모두 `profile was rebuilt after image; detached measurement`로 실패했다. 이미지 빌드는 원격 gha cache의 bootJar layer를 사용했지만 profile export는 같은 cache 입력이 없어 해당 RUN을 다시 실행했다. 검사기의 거부는 올바르며, D-087 전체 완료는 보류한다. [후속 PR216](https://github.com/Kimgyuilli/PeekCart/pull/216)의 첫 수정 CI39607970017도 이미지 잡이 실패해 원인 분리 중이다. 원 D-039 로그의 `jar` 헤더는 28.06초이며 앞선 `compileJava/processResources/classes` 세 헤더만 27.96초다. 역사 본문은 보존하고 이 정정을 상태·진행 기록에 명시한다.

## 2026-10-21 — D-087 cache-hit 계측 보정 병합 후 완료

[PR216](https://github.com/Kimgyuilli/PeekCart/pull/216) 최종 head `613aa478b645e1dcc56862f177aee951b8e16527`은 이미지의 bootJar cache hit를 실제 raw 기록으로 확인하면 profile export를 생략하고 `cache_hit_unmeasured`와 null 수치를 기록한다. fresh 경로의 검증 및 재빌드 거부는 유지한다. [독립 최종 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/216#issuecomment-6318077175)는 12개 검사·16개 변이와 여섯 실제 cache-hit 이미지의 원자료·health를 직접 확인했다. 새 CI에서 fresh 시간은 측정하지 않았으며 PR209의 여섯 fresh 실측은 당시 실행의 근거로만 남긴다.

[PR CI39611388133](https://github.com/Kimgyuilli/PeekCart/actions/runs/39611388133)의 필수 검사·E2E·gate 성공 후 정상 병합 SHA는 `797fc30d1661353aaf3d0d878c0e34f3d0347007`이다. 실제 [main CI39613985180](https://github.com/Kimgyuilli/PeekCart/actions/runs/39613985180)는 여섯 publish를 포함해 모두 성공했다. D-087의 무료 직접 계측·잘못된 구간 해석 정정·cache-hit 안전 처리를 완료로 기록한다. 단일 실행의 수치를 성능 개선이나 운영 천장으로 일반화하지 않는다.

## 2026-10-22 — D-065 수집 판정 무료 범위 부분 병합

[PR189](https://github.com/Kimgyuilli/PeekCart/pull/189) 최종 head `a9dd257374bcee3eeb886a938db72e62662a753d`의 [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/189#issuecomment-6318807417)는 최신 main 통합의 작성 patch 동일성과 실제 16개 판정·9개 수집 검사·9개 drift 대조를 확인했다. [PR CI39614158821](https://github.com/Kimgyuilli/PeekCart/actions/runs/39614158821) 전체 성공 뒤 정상 병합 SHA는 `7915f266500a25be457ce2ec67fc18526d437c5c`다. 실제 [main CI39619377710](https://github.com/Kimgyuilli/PeekCart/actions/runs/39619377710)은 성공으로 종료됐고 여섯 publish가 각각 성공했다. GitHub API의 payment 이미지 job 상태는 내부 `Complete job` 성공·후속 payment publish 성공에도 `in_progress`로 지연 표시돼 그 차이를 보존한다.

이 범위는 replay preflight 수집 판정 보강이며 실제 MySQL/Kafka adapter #212·제약 #202·Flyway #207은 별도 최신 병합 검증이 남았다. 운영 P22/GKE·NOT NULL 실제 적용과 replay 개방은 수행하지 않았고 D-065 전체는 부분 완료다.

## 2026-10-22 — D-093 초기평가 준비 수정 병합, 실제 Grafana 재검증 대기

[PR188](https://github.com/Kimgyuilli/PeekCart/pull/188) 최종 head `560e16b5496ed802d8d5de8bd3c81edf2bf1ac72`는 초기 평가의 실제 vector·Normal을 유한 상한에서 기다리도록 바꾼다. [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/188#issuecomment-6319319025)는 직접 음성 대조와 원본 실행 증거를 확인했고, [PR CI39619631016](https://github.com/Kimgyuilli/PeekCart/actions/runs/39619631016) 필수 검사가 성공했다. 정상 병합한 main은 `8f0d2ee091bc2ce08a4c19e2ca3678d406559d34`다.

실제 [main CI39623073011](https://github.com/Kimgyuilli/PeekCart/actions/runs/39623073011)은 workflow 성공으로 종료됐고 publish 다섯 job은 성공 결론이다. product publish는 Push image와 Complete job을 포함한 모든 단계가 성공했으나 GitHub API의 job 상태는 여전히 `in_progress`/결론 공란이므로 여섯 job 모두 성공 결론이라고 기록하지 않는다. D-092 PR217의 새 Grafana 실행으로 준비 경합 해소와 전달 경로를 재확인하기 전 D-093 전체 완료 표기는 보류한다. 운영 외부 전송·GKE 검증으로 확대하지 않는다.

## 2026-10-22 — D-092 운영 경보 로컬 경로 부분 병합

[PR217](https://github.com/Kimgyuilli/PeekCart/pull/217) 최종 head `85786a4e3f2945b513bcbdf5507ce0ea10412087`의 [전용·일반 CI39623335274](https://github.com/Kimgyuilli/PeekCart/actions/runs/39623335274)는 성공했다. [독립 최종 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/217#issuecomment-6320151203)는 실제 Grafana→로컬 수신기의 Slack형 요청 네 건을 직접 분석해 Outbox/DLQ 발화·복귀 각각 하나의 신호와 두 신호의 정확한 전체 집합, 기본 경로 격리, fixture 정리를 확인했다. 이 실행에서는 D-093의 초기 평가 준비도 통과했다.

정상 병합 SHA는 `7320ffdd4a72a1bdde77bc9424c8d11ecff18ba3`다. 실제 [main CI39629072400](https://github.com/Kimgyuilli/PeekCart/actions/runs/39629072400) 및 publish 검증은 진행 중이다. 실제 Slack·GKE·운영 DB 전송은 검증하지 않았고, D-092는 사용자/운영 웹훅 분리 PR219와 실제 Secret 소유·전환, 외부 전달·FAILED 복구 조건이 남은 부분 완료다. D-093 완료 기록은 병합 후 확인까지 보류한다.

실제 main CI39629072400은 테스트·E2E·gate와 여섯 publish가 모두 개별 성공으로 종료됐다. 따라서 D-093은 PR188의 실제 지연/timeout 대조, PR217에서 초기 준비 통과 뒤 실제 Grafana 평가·전달 검증, 두 병합의 main 검사를 근거로 완료 처리한다. TASKS의 원래 본문을 보존해 완료 아카이브에 옮겼다. D-092는 무료 로컬 경로만 부분 완료이며, 실제 외부 전달과 운영 복구를 확인한 것으로 바꾸지 않는다.

## 2026-10-22 — D-092 사용자·운영 웹훅 분리 부분 병합

[PR219](https://github.com/Kimgyuilli/PeekCart/pull/219) 최종 head `8c55ef3ecfdef8a978fdf15fad8e220ad2514a40`은 사용자 알림과 운영 경보를 별도 필수 URL로 바꾸고 누락·동일 endpoint 설정을 시작 시 거부한다. [독립 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/219#issuecomment-6320677035)는 사용자·운영 loopback 분리, 실패 설정 음성 대조, signed 경로 증거를 직접 확인했다. [일반 CI39629291268](https://github.com/Kimgyuilli/PeekCart/actions/runs/39629291268)과 signed CI39629291259가 성공했다. 정상 병합 SHA는 `b2e862085de695f601725717a7eeae68ca83aecf`이고 실제 main CI39632767287과 여섯 publish는 진행 중이다. 실제 두 Secret의 소유·값·전환 및 외부 Slack/GKE 전달은 여전히 미검증이며 D-092 전체 완료가 아니다.

실제 [main CI39632767287](https://github.com/Kimgyuilli/PeekCart/actions/runs/39632767287)은 테스트·E2E·gate와 여섯 publish가 모두 개별 성공했다. 따라서 웹훅 분리의 무료 코드/로컬 범위는 병합 후 검증까지 마쳤다. 운영 Secret 소유·값·전환 순서, 외부 Slack/GKE 전달과 FAILED 운영 복구는 실제 증빙 전까지 D-092의 잔여 조건이다.

## 2026-10-22 — D-065 실제 수집기 어댑터 무료 범위 병합

[PR212](https://github.com/Kimgyuilli/PeekCart/pull/212) 최종 head `cd1cbb1bb649e673a3650f4a6952f3b820d78ed8`은 replay preflight의 실제 MySQL/Kafka 읽기 어댑터를 로컬 분리 fixture에 연결한다. [독립 최종 리뷰](https://github.com/Kimgyuilli/PeekCart/pull/212#issuecomment-6321171226)는 452개 원본 증거 파일·11사례·검사기 결함 변이와 정리를 직접 확인했고, [전용 CI39633001119](https://github.com/Kimgyuilli/PeekCart/actions/runs/39633001119) 및 일반 CI39633001050이 성공했다. 정상 병합 SHA는 `e45d3211e2cf30a0a4e220d80b2d397074467b81`이며 실제 main CI39636215009/여섯 publish는 진행 중이다.

운영 표본·GKE·NOT NULL 실제 적용·replay 개방은 수행하지 않았다. D-065는 계속 부분 완료이고 replay kill-switch는 닫혀 있다.

실제 [main CI39636215009](https://github.com/Kimgyuilli/PeekCart/actions/runs/39636215009)는 전체 테스트·두 E2E·gate와 여섯 publish가 모두 개별 성공했다. 따라서 PR212의 무료 로컬 수집기 범위는 병합 후 검증까지 완료했다. 운영 표본·GKE·NOT NULL 실제 적용·replay 개방은 여전히 남는다.

## 2026-10-22 — D-075 후속 정책 결정 보류

사용자가 D-075 티켓의 후속 결정을 보류했다. [PR200](https://github.com/Kimgyuilli/PeekCart/pull/200)의 무료 로컬 병목 분석과 병합 후 검증은 유지한다. 목표 TPS, 최대 backlog, 초과 시 대응 정책은 정하지 않았고, 후속 선택·정책 검증은 사용자가 티켓 재개와 기준을 알려줄 때 진행한다. 이 보류를 전체 완료로 표시하지 않는다.

## 2026-10-22 — 부채 캠페인 직렬 병합 마무리

남은 PR을 최신 main에 맞춘 뒤 PR CI 성공과 직전 main CI 성공을 확인하고 하나씩 병합했다. 순서는 #190·#202·#207·#214·#222·#191·#192·#221·#220·#213·#208·#199·#197·#195·#215·#194·#218이다. 마지막 [main CI39699130969](https://github.com/Kimgyuilli/PeekCart/actions/runs/39699130969)(`985f550`)까지 테스트·E2E·gate와 여섯 publish가 성공했다.

- #214 병합 뒤 main lint가 `backfill transaction did not reach observed pause`로 실패했다. [PR222](https://github.com/Kimgyuilli/PeekCart/pull/222)의 첫 CI에서 runner의 `docker exec mysqladmin ping`이 3초 timeout을 넘는 것을 확인했다. 폴링 한 번이 3초 SLEEP 구간보다 길어 정지 구간을 놓친 것이 원인이다. writer SLEEP을 20초로, 관측을 PROCESSLIST 기준으로 바꾸고 timeout을 늘렸다. 이후 main CI는 모두 성공했다.
- #221 병합 뒤 main의 일시적 의존성 다운로드 실패(`lombok-1.18.44.jar`)는 실패 job 재실행으로 성공했다.
- #213은 wiring lint가 `python3 -m` 이후 step 스캔을 멈춰 inline self-test를 못 찾았고, CI 줄 순서만 바꿔 통과시켰다. #197은 #205와 같은 주석 위치에서 충돌해 main 코드와 #197 주석을 함께 남겼다. #194는 완료 아카이브 D-050 정정을 corrections manifest로 선언했다.

D-086·D-088·D-091·D-095·D-096·D-100·D-101·D-102·D-104는 각 계획의 완료 기준을 PR 하나로 충족했고 병합 후 main CI 성공을 확인해 완료 아카이브로 옮겼다. D-064·D-065·D-078·D-084·D-089·D-092는 무료 로컬 범위만 병합한 부분 완료다. D-089의 publication-state job은 main에 연속 push가 있으면 이전 상태를 보고할 수 있다(PR215 P2 코멘트). D-075는 사용자 재개 전까지 보류한다.

## 2026-10-22 — D-078 과금 세션 사전 준비

README 개편 측정 수요를 D-078 에 합치고 과금 세션 전의 계측 공백을 메웠다([PR225](https://github.com/Kimgyuilli/PeekCart/pull/225)). product 소비 재시도 메트릭(`kafka.listener.delivery.failed`, `kafka.listener.recovered`)을 추가해 ⑤ 의 충돌 수를 Pod 로그 대신 메트릭으로 센다. ⑥ 은 Boot 가 바인딩하는 `taskScheduler` executor 메트릭으로 보며 그 가정을 테스트로 고정했다. 주문·경합 측정용 `gke-d002bc-mysql-2000m` overlay 를 추가했다.

Phase 4 증적을 다시 읽어 1,000 VU 동시 주문 재측정은 넣지 않았다. `d002bc` ① 과 경합 run 이 README 목적의 질문에 답한다. D-078 ①~⑨ 는 모두 열려 있고 세션 대시보드·캡처 체크리스트·세션 계획서는 후속이다.

## 2026-10-23 — D-078 측정 대시보드와 캡처 기준

#225 를 병합했다. 측정 세션에서 볼 "PeekCart — Saga & Messaging" 대시보드와 `loadtest/captures.md` 를 추가했다([PR226](https://github.com/Kimgyuilli/PeekCart/pull/226)). 건수의 정본은 세션 전후 `/actuator/prometheus` 원값 차분으로 두고 그래프는 보조 증거로 둔다. 재시도 카운터가 첫 실패 때 생성되어 `increase()` 가 첫 1건을 놓치기 때문이다.

측정용 GCP 결제는 새로 준비했다. 결제 계정을 유료로 전환하고 월 예산 ₩20,000(알림 50/90/100/150%)을 걸었다. 지출 한도(spend cap)는 Compute Engine·GKE 를 지원하지 않아 쓰지 않는다. Phase 4 세션의 `peekcart-gke` 프로젝트는 소유 계정이 삭제돼 접근할 수 없으므로, 세션은 새 프로젝트에서 이미지를 다시 올려 진행한다. ⑦(#129 익일 Billing)은 무료 크레딧 계정이라 확인하지 않기로 사용자가 정했다.

## 2026-10-23 — D-078 GKE 측정 세션 계획

#226 을 병합했다. 과금 세션 절차(S0 준비 ~ S5 정리)와 조건 파일 두 개를 추가했다([PR227](https://github.com/Kimgyuilli/PeekCart/pull/227)). ⑤ 의 경합 조건(product replicas 3, outbox 폴링 500ms·500건)을 `gke-d078-contention` overlay 로 고정했다. Phase 4 에서는 두 값이 파일 밖에 있었다. 관측 스택은 별도 노드풀로 격리해 d026 기준선 비교와 노드 CPU 측정을 오염시키지 않는다. 키쌍은 로컬 dev 개인키가 커밋된 ConfigMap 과 짝임을 확인해 새로 만들지 않는다. 세션은 아직 실행하지 않았다.

## 2026-10-23 — D-078 세션 S0 실행과 캡처 도구

#227 을 병합했다. 세션 S0 을 실행했다: 프로젝트 `peekcart-d078` 결제 연결, API 활성화, 서명 키 2개 업로드(업로드 전후 fingerprint 대조), 키별 GSA 1:1 권한, main `4bed313` 이미지 6개 AR 승격(GHCR digest 와 동일). 클러스터·VM 은 없다. Slack webhook 시크릿은 자리만 만들었고 값은 사용자가 넣는다.

캡처 경로를 과금 전에 로컬에서 끝까지 돌렸다([PR228](https://github.com/Kimgyuilli/PeekCart/pull/228)). 대시보드 패널에 고정 id 를 넣고 annotation·단독 패널 URL·원 시계열 export 스크립트를 추가했다. 같은 클러스터에서 확인할 수 있는 D-065 ②⑤와 D-092 전달 canary 를 세션 S4 에 넣었다. 배포 래퍼의 drain preflight 가 빈 namespace 첫 배포를 fail-closed 로 막으므로, 첫 배포는 사유를 남겨 건너뛰고 스택이 뜬 뒤 preflight 를 다시 돌려 D-065 ② 를 확인한다.

## 2026-10-23 — D-078 세션에 README 보강 지표 편입

#228 을 병합했고 Slack webhook 2개를 사용자가 시크릿에 넣었다(값은 보지 않고 형식과 상이함만 확인). 후보 지표를 기존 증적과 대조해 비어 있는 것만 세션에 더했다([PR229](https://github.com/Kimgyuilli/PeekCart/pull/229)). 사가 지연 분포는 Phase 4 계획 P7 이 요구했지만 산출되지 않은 공백이었다. gateway 오버헤드는 기존 수치(+9.9ms)가 있어 뺐다. 사가 왕복과 outbox 발행 대기 추출 SQL 과 집계를 로컬 MySQL 에 실제 Flyway 스키마를 올려 검증했다.

## 2026-10-24 — D-078 GKE 측정 세션 실행

#229 를 병합한 main `312ff16`(이미지 `4bed313`)으로 과금 세션을 실행했다(`evidence/d078-gke-20261024.md`). 클러스터는 약 1시간 50분 살았고 `cleanup.sh` 7종 `[0]` 뒤 결제 연결을 끊었다. 서명 키·Slack webhook 시크릿과 GSA 는 재측정을 위해 남겼다.

S2 읽기 경로(①②③④)는 미측정이다. d026 이후 들어온 ADR-0037 조회 bulkhead(DB 진입 동시성 1, 초과 즉시 503)가 캐시 OFF 경로를 직렬화해 재현 게이트가 구조적으로 실패했다. 사용자 결정으로 S2 를 접고 발견 문제를 모아 고친 뒤 다시 재기로 했다. 경합(⑤)은 jitter 재시도가 낙관락 충돌 대부분을 흡수해 DLQ/주문 0.040~0.068% 였다(Phase 4 0.072%). product outbox 발행률이 기본 폴링 상한 20건/초에 붙어 사가 왕복 p50 이 150~270초였고, 배수가 예약 lease 보다 길면 결제 대기 주문 수천 건이 만료 취소됐다. ⑧ base 2000m 실적용, D-092 두 채널 분리 전달, HPA 1→3(부하 후 93초 전원 Ready)을 확인했다. D-065 ② preflight 는 Kafka 포트 고정으로 실제 스택에서 실패했고 ⑤ 는 payment 토픽 6개가 선언값을 받지 못했다. 세션 발견 11건은 `loadtest/reports/2026-10-24/session-issues.md` 에 있고 D-106 한 묶음으로 등록했다. 미측정 항목은 이 묶음을 고친 뒤 다시 잰다.
