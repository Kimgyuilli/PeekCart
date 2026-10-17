---
grade: L
---
# 남은 부채 병렬 처리 — root 조율 · 독립 PR 검증 · 무료 CI 우선

기준: 2026-10-17, main `92755566074fc28d78e53dd84b2b085a7fbfdb38`.
이 문서는 기존 D- 작업을 배분하는 상위 실행 계획이다. 새 제품 부채 번호를 만들거나 개별 D- 계획을 대체하지 않는다.
최초 작성 세션은 계획만 작성했다. 2026-10-17 후속 사용자 요청으로 구현·검증·커밋·push·PR·병합·기록 실행이 승인되었다. 무과금 조건과 제외 범위는 유지한다.

## 1. 명제

대상 부채 중 하나라도 필수 증적 없이 완료되거나, 작업자가 자기 PR의 최종 검증·병합을 수행하거나, 검증 후 변경된 코드가 재확인 없이 병합되거나, 무거운 로컬 실행이 중첩되거나, 추가 과금이 발생하는 경로를 사용하면 이 계획은 미완이다.

사용자 결정: root는 오케스트레이터이며 병합·최종 기록을 담당한다. 작업자는 `sync → plan → work → ship`을 수행한다. 별도 서브에이전트 하나가 PR을 검증한다. GitHub Actions로 가능한 검증은 위임한다. 과금이 필요한 부채는 범위에서 제외한다.

등급 L: 전체 실행은 여러 모듈과 공용 API·이벤트·테스트 계약에 걸치며, 잘못된 통합을 되돌릴 때 작업 간 조정이 필요하다. 각 D- 작업은 별도로 S/M/L을 판정한다.

## 2. 배경과 코드 검증

| 확인한 전제 | 현재 코드/기록 | 계획에 반영 |
|---|---|---|
| 열린 부채 | `docs/TASKS.md`에 20행 | 아래 표에서 20개 모두 처분; 제외는 완료로 바꾸지 않음 |
| 로컬 CPU 경합 | `docker-compose.e2e.yml`에 4 JVM 동시 기동이 기존 220초 창을 초과한 기록 | 로컬 heavy 슬롯 1개 |
| 컨테이너 공유 범위 | `common/src/testFixtures/java/com/peekcart/support/SharedContainers.java`의 static MySQL/Redis/Kafka | JVM 내부 공유이며 에이전트 간 공유가 아님; Gradle 실행 증가 시 스택 증가 |
| 메모리 제한 | E2E 일반 시나리오의 컨테이너 제한 합 약 6.875 GiB | 실제 사용량/필요 RAM 추정치로 사용하지 않음; Docker VM·Gradle·OS 여유 별도 |
| 실행 상한 | root/서비스 Gradle 설정에서 명시적 maxParallelForks/worker 제한 미발견 | 로컬 명령에 `--no-parallel --max-workers=1 --no-daemon`; JUnit 병렬화 추가 금지 |
| CI 전체 검증 | `.github/workflows/ci.yml`: 6 test shard의 모듈별 `:build`, root `:build` guards, lint, images, E2E 2모드, 최종 gate | 모듈 단위 로컬 테스트만으로 완료하지 않고 CI 전체 증적으로 검증 |
| CI 최종 계약 | `gate.needs=[lint,test,guards,images,e2e]`, ADR-0030 | 초록색 test job 하나가 아닌 최종 gate와 필수 산출물 확인 |
| CI 실행 범위 | PR/main push에서 실행; `ci-${github.ref}`별 이전 실행 취소 | PR 생성 시에도 CI가 실행됨; 무료 사용 사전 확인. 다른 PR끼리의 전체 실행 수는 root가 조율 |
| 로컬 이미지 충돌 | E2E 기본 이미지 태그 `ci`, project/run ID는 분리 가능 | 로컬 태그 `<task>-<sha>`와 고유 run ID; 공유 ci 태그 덮어쓰기 금지 |
| smoke 격리 한계 | `docker-compose.yml` 고정 container_name/포트, `scripts/docker-health-smoke.sh` 시작 cleanup | project명만 바꾼 동시 실행 불가; 기존 사용자 스택 확인 후 실행 |
| 테스트 기반 부채 | `AbstractIntegrationTest.cleanKafkaTopics`가 모든 describe 예외를 생략, MySQL은 `mysql:8.0` | D-083/D-068 먼저 통합해 후속 검증 기준 안정화 |
| 상품 조회 충돌 | ProductQueryService/ProductCacheService 모두 클래스 `@Transactional`; 목록 키에 sort 없음 | D-066/072/069 직렬 배정 |
| E2E 충돌 | `saga_e2e.py`: `LINGERING_SCENARIOS=[]`, 최초 attempt_count=1만 단언 | D-061/062 같은 담당자가 순차 처리 |
| Kafka retry | Product만 jitter, Order/Payment/Notification/DeadLetter는 FixedSequenceBackOff | D-076은 공용/다서비스 변경으로 취급 |
| 복구 계약 | ADR-0035는 증빙 기반 강제 종결·과거 수동 FAILED 복구를 제공하지 않음 | D-084는 새 ADR과 안전 계약 선행 |

호스트 CPU/RAM, Docker VM 할당, GitHub 저장소 공개 여부·무료 잔여량·실제 branch protection은 이번 세션에서 확인하지 못했다. sysctl 조회는 권한 제한으로 실패했다. GitHub API도 앞선 sync에서 연결 실패했다. 실행 시작 때 확인하며 확인 전 수치나 무료 실행을 가정하지 않는다.

### 범위와 완료 증거

`대상` 16건 + `조건부` 1건 + `제외` 3건 = 열린 부채 20건. 순서는 코드 필수 의존성과 충돌 방지를 위한 운영 순서를 구분한다.

| ID | 처분 / 권장 선행 | 소유 표면과 완료 증거 |
|---|---|---|
| D-083 | 대상, 첫 묶음 | 공용 테스트 cleanup: UnknownTopic만 생략, timeout/브로커 실패는 cleanup에서 드러나는 회귀 검증 |
| D-068 | 대상, 첫 묶음 | SharedContainers·Compose/E2E·k8s·독립 테스트의 MySQL 버전 조사/고정; 통합·실행계획 계약 검증. 실제 클러스터 배포 제외 |
| D-080 | 대상, D-083 뒤(같은 파일) | 원본 artifact/재현 자료/바이트코드에 근거해 기동비용·종료 꼬리·셔플 설명 정정. 증거 부재 시 추정으로 수치 확정 금지 |
| D-067 | 대상, 공용 기반 뒤 | GlobalExceptionHandler와 소비 컨트롤러: 잘못된 타입/validation 4xx, 유효 요청·기존 도메인 오류 보존 |
| D-066 | 대상, D-067 뒤 권장 | 상품 정렬·tie-breaker·정렬별 캐시 분리, 양방향 캐시 오염 회귀, invalid sort 4xx. 커서 전환을 임의로 추가하지 않음 |
| D-072 | 대상, D-066 뒤(같은 표면) | 캐시 hit 시 서버측 MySQL 명령 0, miss 트랜잭션/직렬화 보존, advisor 순서 명시 검증. GKE 인과 측정은 D-078로 제외 |
| D-069 | 대상, D-072 뒤 | Redis 장애 동시 요청의 DB 진입 상한·응답/거절·회복 검증. SLO/허용 동시성 대안을 개별 plan에서 확정; 무근거 숫자 금지 |
| D-071 | 대상, 상품 lane과 병렬 가능 | Order 장바구니 read model/DTO/DDL: 이름·상태·가격, 미수신/역전/재처리 정책 및 호환성. 공유 이벤트 변경 시 root 조정 |
| D-070 | 대상 | cache fallback alert get 지속/evict 즉시 계약, 라벨·정적 lint·runbook. D-063에 실제 발화 검증 연결 |
| D-063 | 조건부, D-070 뒤 | 무료 CI/로컬 실제 Prometheus·Grafana에서 series→firing→resolved 증적. 기존 GKE 요구와 동등하게 충족 가능한지 plan에서 판정. 클러스터 고유 검증/유료 인프라가 필수면 이번 범위에서 제외하며 완료 표시 금지 |
| D-076 | 대상, 공용 기반 뒤 | 4개 handler jitter 범위/시도 횟수/DLQ 계약. 공용 messaging 변경과 다른 lane의 KafkaConfig 수정 충돌 통제 |
| D-075 | 대상, D-076 뒤 권장 | outbox 병목을 무료 로컬 환경에서 분리, 목표 처리율·지연과 변경/명시적 수용 결정, 중복·순서·실패 복구 검증. GKE 성능 일반화 금지; 유료 측정이 완료에 필수면 미완으로 남김 |
| D-061 | 대상 | 환불 3종×소비자 3곳 종착, stub POST/GET 순서, lingering 순서 가드; 실제 CI 스택 증적 |
| D-062 | 대상, D-061 뒤(같은 파일) | 같은 DLT 좌표 rewind→원장 1행/attempt=2, group 정지·원복과 후속 시나리오 무간섭 |
| D-084 | 대상, 설계 선행 | Payment/Product 복구: 권한·근거·감사, PG/예약/환불 대사, 과거 FAILED 포함, 중복/동시 reconciler에도 재고 1회. 영구404/시간 경과만으로 해제 금지. 실 결제·운영 데이터 실행 제외; stub은 실 PG 증거로 주장하지 않음 |
| D-073 | 대상, 문서 소유권 직렬화 | 원본 세션/git/PR과 측정 서술 4곳 대조, immutable 증적은 정정 추기; 원본 없으면 근거 대기 |
| D-077 | 대상, 문서 소유권 직렬화 | ADR-0025·현행 낙관락 코드 및 비동기 취소 이벤트에 맞춰 Layer 1/swagger 정정 |
| D-064 | 제외 | 실 PG 호출/거래의 무과금 가능성을 미확인했으므로 보수적으로 제외. 모든 테스트 PG가 유료라고 단정하는 것은 아님. 재편입은 무료 조건 확인 후 별도 범위 결정 |
| D-065 | 제외 | GKE 적용·운영 표본·권한·replay 개방 리허설이 완료 요건. 단순 kill-switch 개방/부분 PR로 닫지 않음 |
| D-078 | 제외 | GKE 부하/노드 경합/실배포/Billing 확인 포함. 무료 로컬 결과로 대체해 완료하지 않음 |

### ADR와 블라인드스팟

오케스트레이션 자체는 제품 아키텍처를 바꾸지 않으므로 신규 ADR을 만들지 않는다. D-084는 ADR-0035의 운영 한계를 바꾸므로 신규 ADR 필수. D-066/069/071/072/075는 공개 계약·장애 정책·트랜잭션/발행 경계의 최종 선택에 따라 ADR을 개별 plan에서 판단한다. 기존 원칙의 구현인 D-076은 ADR-0025를 따른다. 사실 정정과 결정 변경을 구분해 `docs/adr/README.md` 규칙을 적용한다.

상위 계획은 코드 이동/모듈 분리를 지시하지 않는다. B1/B1b/B5/B6/B8/B10은 구조 변경 선택 시 개별 plan에서 재점검한다. B2/B4는 위 코드 확인과 파일 소유권에 적용했다. B3은 D-068/083 공용 testFixtures 유지, B7/B9는 D-071/084의 역전·원자 갱신, B11은 D-070/063 라벨/식 lint, B12는 D-084 실제 DB 경합 검증에 적용한다. mock만으로 잠금·원자성 완료를 주장하지 않는다.

## 3. 역할과 작업 흐름

동시 에이전트 4개: **root 1 + worker A/B 2 + 독립 PR verifier 1**. verifier는 해당 PR 구현자가 아니며 구현을 대신 수정하지 않는다. 원 작성자가 수정 후 재검증한다. worker의 추가 하위 에이전트 생성은 금지해 슬롯·소유권을 유지한다.

1. root가 최신 main, 범위, 무료 실행 조건, 파일 소유권, 선행 PR을 확인해 작업을 배정한다.
2. worker마다 별도 branch/worktree를 사용한다. 공유 checkout에서 branch 전환/동시 편집하지 않는다. verifier도 분리 checkout에서 지정 PR을 읽는다. 모든 도구에 workdir를 지정한다.
3. worker는 각 작업에서 `sync → plan → work → ship`을 수행한다. sync는 상태 확인·아카이브 preview까지; 이동·공통 상태 갱신은 root에 전달한다. plan은 해당 D-ID, 등급, ADR, P항목, 실패를 드러내는 검증을 작성한다.
4. work에서 구현과 빠른 회귀를 끝내고 전체 검증은 CI 대기로 명시한다. 사용자 요청에 따라 기존 work 스킬의 로컬 `./gradlew test` 의무를 **같은 변경에 대한 CI 전체 모듈 build/test + root guards + 필수 gate 증적**으로 대체한다. 검증 자체를 생략하지 않는다.
5. CI는 PR에서 시작되므로 모든 검증이 끝나야 ship한다는 기본 규칙에 한해 예외가 필요하다. worker는 **검증 대기 Draft PR**을 ship 실행으로 생성한다. PR 본문에 CI 대기/미충족을 표시하고 관련 P항목은 열린 상태로 둔다. 이 단계는 완료가 아니다. CI 없는 초록색 완료 표시는 금지한다.
6. CI 성공 후 worker가 실행 URL·SHA·결과·artifact를 보고하고 verifier가 독립 검증한다. root가 필요 시 작업별 plan/audit 체크 갱신을 조율한다. 문서 커밋으로 SHA가 바뀌어도 최종 커밋의 CI를 다시 기다린다.
7. verifier는 코드/요구사항/ADR/반례, 전체 diff(신규 파일 포함), 테스트가 잘못된 구현을 잡는지, CI 결과·산출물의 동일 커밋 여부를 확인한다. 발견 사항은 위치·재현·영향·수정 조건으로 root에 보고한다. 테스트 재실행이 필요하면 같은 로컬 슬롯 또는 무료 CI 큐를 사용한다.
8. root만 최종 merge한다. PR별로 하나씩 처리하며 base 최신화 후 검증한다. merge 직전 PR head/base와 검증 기록을 재확인하고 변경되면 다시 검증한다. 요구 check 우회/강제 push/검증 무시 병합은 하지 않는다.
9. root가 main push gate 결과를 확인하고 `done`으로 TASKS/PHASE/ADR 상태/Layer 1/plan archive·index를 정리한다. 후속 기록 커밋도 정상 PR/CI 경로를 사용하며 직접 main push로 보호를 우회하지 않는다. 기록 전용 후속 PR은 여러 완료 작업을 묶을 수 있다. merge 사실과 배포 사실은 구분한다.

사용자 요청은 이 세션에 한해 기존 AGENTS/스킬의 별도 reviewer 미호출 원칙을 **독립 PR 검증 서브에이전트**로 재정의한다. `.claude`의 `codex exec` 리뷰 파이프라인은 호출하지 않는다. audit에는 `별도 codex exec 리뷰 미호출; 독립 PR 서브에이전트 검증: <실제 상태/대상 SHA/기록>`처럼 두 절차를 구분한다. 이번 계획 분석에 참여한 에이전트는 PR verifier가 아니며 리뷰 통과를 주장하지 않는다.

ship가 보통 수행하는 done/완료 기록은 사용자 지정대로 root에 위임한다. worker는 코드와 필요한 기술 문서 초안·자기 plan/audit를 제공한다. 공용 TASKS/PHASE/ADR index/Layer 1 문서는 root가 단독 편집하거나 특정 worker에게 파일 단위 독점권을 임시 위임한다. API 문서 등 코드와 같이 검증되어야 하는 내용은 PR 전에 root가 해당 branch에 반영하고 최종 SHA 검증을 받는다. ADR 번호도 root가 예약해 중복을 막는다.

### 배정 순서

- 준비: 무료 조건/원격 연결/CI 보호 확인, 격리 worktree·검증 큐·실행 기록 형식 확정.
- 1차: A=D-083→D-068→D-080, B=D-073→D-077. 공용 문서 충돌은 root가 직렬 반영한다.
- 2차: A=D-067→D-066→D-072→D-069, B=D-076→D-071→D-075. common/이벤트 변경이 겹치면 선행 병합을 기다린다.
- 3차: A=D-061→D-062, B=D-070→D-063(무료 동등성 충족 시).
- 4차: D-084 구현·통합. 설계 조사/대안 마련은 앞선 단계의 CI 대기 시간에 시작할 수 있지만 Payment/Product 계약 변경은 다른 lane의 같은 표면 수정이 끝난 뒤 통합한다.

이는 고정 날짜 일정이 아니다. root는 선행 완료·파일 소유권·CI 여유에 따라 준비된 작업을 먼저 배정한다. 동시 미완 구현/검증 PR은 원칙적으로 2개 이내로 유지해 검증 대기열을 쌓지 않는다. verifier가 비어 있으면 읽기 전용 다음 작업 조사를 맡길 수 있으나 자기 구현 PR 검증은 하지 않는다.

## 4. CI와 로컬 자원 운영

### 무료 실행 조건

GitHub 공식 문서는 public 저장소의 standard GitHub-hosted runner 사용이 무료이며 private 저장소는 포함량 범위가 있음을 설명한다. 저장소 가시성/러너 종류뿐 아니라 artifact·cache·GHCR의 무료 조건과 기존 사용량도 실행 전에 확인한다. 출처: https://docs.github.com/en/billing/concepts/product-billing/github-actions (2026-10-17 확인).

유료 larger runner, GKE 생성/기동/측정, 실거래, 추가 결제 설정, 예산 증액은 하지 않는다. 무료 여부를 확인할 수 없거나 무료 한도가 소진되면 원격 실행을 보류하고 독립적인 로컬 작업을 계속한다. CI를 우회해 병합하지 않는다. 현재 main push는 gate 뒤 GHCR publish를 수행하므로 병합의 부수 효과와 저장소 비용 조건도 사전 확인한다. 이번 작업에서 자동 배포를 추가하지 않는다.

### 검증 분담

| 실행 위치 | 담당 |
|---|---|
| 로컬 경량 | 소스 탐색/편집, diff/문서 검토, Docker/Gradle을 호출하지 않는 lint |
| 로컬 heavy 슬롯 1 | 필요한 집중 회귀·CI 실패 재현, 서버측 계수/동시성 탐침, 무료 로컬 monitoring/성능 실험 |
| GitHub Actions 기본 | 전체 모듈 build/test, root guards, 정책 lint, 이미지 build/health smoke, saga E2E scenarios/negative-control, 최종 gate |
| verifier | CI 확인 + 독립 코드/계약 검증. 모든 테스트를 이유 없이 중복 실행하지 않음 |

현재 CI에 없는 새 검증(예: D-063 실제 monitoring)은 각 작업에서 무료 runner 안에 배선하거나 로컬 실행 증적을 추가한다. 기존 CI 성공만으로 새 완료 조건이 충족됐다고 간주하지 않는다. `gate` 이름/실패·skip 전파/이미지 identity 계약은 유지한다.

로컬 heavy는 root가 `task, owner, worktree, SHA, command, start, PID/session, evidence path`를 기록하고 승인한 한 실행만 시작한다. Gradle, Docker build, E2E, smoke, 부하 발생 모두 같은 슬롯이다. 종료·실패·취소 시 프로세스와 해당 실행의 컨테이너 정리까지 확인한 뒤 반환한다. timeout만 보고 슬롯을 재할당하지 않는다. 다른 작업/사용자의 컨테이너를 제거하지 않는다. 공유 Docker daemon에 대한 일괄 prune은 하지 않는다.

실행 시작 시 단일 작업의 시간·peak 메모리·Docker CPU와 기존 스택을 확인한다. 하드웨어/VM 할당과 여유를 확인하기 전 슬롯을 늘리지 않는다. Gradle 옵션은 JVM heap/Docker CPU의 절대 상한이 아니다. JUnit 클래스 병렬화는 공유 DB/Kafka cleanup 계약 때문에 도입하지 않는다.

CI는 다른 runner에서 실행되므로 로컬 heavy와 병행 가능하다. 기존 workflow의 PR별 취소는 다른 PR의 실행을 제한하지 않는다. root는 active PR CI를 최대 2개로 시작하고 무료 quota/대기 상황에 따라 낮춘다. 모든 PR job matrix의 병렬 수를 무작정 줄이거나 새 self-hosted runner를 만들지는 않는다. 실행 취소/실패의 원인을 기록하고 같은 코드의 무제한 재시도로 우연한 green을 채택하지 않는다.

### 증적과 중단 복구

root는 실행 시작 시 `.cache/debt-orchestration/`에 상태를 보관하고, 최종 근거는 해당 plan/audit·PR 기록에 남긴다. 캐시만 남기고 완료하지 않는다. 상태 필드는 D-ID, 담당, branch/worktree, base/head SHA, 선행, 파일 소유권, plan 상태, CI run/attempt/실제 checkout SHA, PR URL, verifier 대상 SHA/결론, merge SHA, 남은 조건이다.

PR CI에서 실제 검사한 checkout SHA와 PR head/base SHA를 각각 기록한다. 이전 head의 green, 다른 PR artifact, 취소/skip, 산출물 누락을 통과로 세지 않는다. 같은 run의 실패 job 재실행은 성공 job 증적의 대상 코드가 동일할 때만 인정한다. artifact 보존 만료 전에 필요한 비밀 없는 요약·로그를 보관한다.

재시작 시 root는 메모리 속 진행률 대신 git/PR/CI/프로세스/컨테이너 사실을 다시 확인한다. 작업자 이탈 시 변경을 보존하고 소유권을 회수한 후 인계한다. 파일 충돌 해결·base 변경·추가 commit 뒤 verifier 판정은 재확인한다. main push 실패 시 다음 병합을 멈추고 해당 변경을 원인 조사/수정하거나 검증된 되돌림으로 처리한다.

## 5. 작업 항목

- [x] P1. 실행 전 무료 조건·원격 접근·branch protection·하드웨어·잔여 프로세스 확인, root/worker/verifier 역할과 worktree·CI/로컬 큐 마련.
- [x] P2. 개별 D- 계획을 순차 수립하고 공용 기반 D-083/068/080 및 정정 D-073/077 처리.
- [ ] P3. API/상품/장바구니 D-067/066/072/069/071 처리. 정책·ADR 결정과 반례 검증 포함.
- [ ] P4. 메시징/처리율 D-076/075 및 saga E2E D-061/062 처리.
- [ ] P5. D-070과 무료 동등성 확인 후 D-063 처리. 충족 불가 시 제외 이유·잔여 조건 기록.
- [ ] P6. D-084 ADR·복구 정책 확정 후 구현·실제 DB 경합 및 CI 스택 검증. 운영/실 PG 실행은 하지 않음.
- [ ] P7. 각 PR의 최신 코드 CI/독립 검증→root 직렬 병합→main 확인→done/아카이브/인덱스 기록을 완료.
- [ ] P8. 대상 16건과 조건부 1건의 처분을 대조하고, 제외 3건 및 새로 발견한 비용/증거 차단 항목을 열린 상태로 최종 보고.

체크박스는 실행 완료 증거가 충족될 때만 체크한다. 현재 진행 상황과 차단은 audit의 2026-10-17 실행 절을 따른다.

## 검증 방법

| 잘못된 실행을 드러내는 상황 | 기대 판정 |
|---|---|
| worker와 verifier가 동시에 로컬 Gradle/E2E 요청 | 하나만 실행, 나머지는 큐; 무거운 명령을 간접 호출하는 lint도 동일 적용 |
| PR push 뒤 이전 커밋 green 제출 / base 갱신 / 신규 파일 누락 | 최신 head/base/checkout/diff 증거와 불일치하면 병합 차단 |
| test 성공, E2E 실패·skip 또는 artifact 누락 | gate·verifier 실패; 완료 표시 금지 |
| malformed 타입, 잘못된 sort, Redis 장애, Kafka cleanup timeout 주입 | 각 D- 계획의 4xx/격리/오류 전파 계약으로 실패가 관측됨 |
| DLT 같은 좌표 재소비, 이벤트 역전, 중복 복구와 reconciler 경합 | 원장/DB/호출 ledger로 횟수·상태 불변식 확인; mock 통과로 대체 금지 |
| 무료 조건 미확인/소진, 실제 GKE·PG 필요 발견 | 원격·과금 작업 보류/범위 제외, 다른 준비 작업 진행; 부채는 열린 상태 |
| main 병합 뒤 검증 실패 | 다음 병합 중단, 원인 분리 후 수정/되돌림 검증; 완료 기록은 사실대로 정정 |

계획 자체는 task ID 검증, frontmatter/필수 절/P-ID 확인, 열린 D-ID 20개와 표의 일대일 대응, `git diff --check`로 확인한다. 최초 계획 작성 세션에서는 제품 코드·테스트·CI·워크플로를 수정하거나 실행하지 않았다. 후속 실행은 사용자 승인 범위에서 진행하며 실제 결과는 audit에 기록한다. 별도 codex exec 리뷰 미호출.

## 완료 조건과 미해결

실행의 완료는 **이번 무과금 범위의 각 부채가 필요한 증거·독립 PR 검증·root 병합·기록을 갖춘 것**이다. 제외된 D-064/065/078까지 해결했다고 보고하지 않는다. D-063 등에서 무료 환경으로 충족 불가가 밝혀지면 범위 처분을 명시한다. 대상의 증거가 막힌 상태는 전체 완료가 아니라 부분 완료/차단이다.

아직 결정하지 않은 사항은 무료 CI 실제 사용 가능 여부, D-063 환경 동등성, D-069 SLO·동시성 상한, D-075 목표/수용 기준, D-071 미수신 상품 표시, D-084 증빙·권한 정책이다. 해당 worker가 개별 plan에서 코드 근거와 권장안을 구체화해 root에 올린다. 사용자 의도가 필요한 정책 선택은 그때 집중해서 확인하고, 독립 작업은 계속한다. 새로운 외부 의존/인프라를 추가하는 선택은 임의로 확정하지 않는다.


### 실행 중 확정 사항 (2026-10-17)

초기 미확인 항목의 최신 판정은 다음과 같다. 공개 저장소·표준 runner·GHCR 무료 조건을 확인했고, 사용자가 artifact/cache 무료 범위와 유료 cache 확대 비활성 여부를 확인했다. 호스트 8 CPU/16 GiB 및 Docker 8 CPU/약 7.65 GiB를 확인했으며 로컬 heavy 슬롯은 1개를 유지한다.

D-069는 사용자가 DB 보호와 초과 요청 503을 승인했다. D-071의 미수신 metadata는 null을 보존하고 품목을 삭제하거나 가격 0으로 대체하지 않는 계약을 ADR-0036에 기록했다. D-063은 ADR-0015의 실제 규칙 평가를 무료 로컬 Prometheus/Grafana로 검증하는 방향이며 실제 발화·정상 복귀 증거가 있어야 완료할 수 있다. 클러스터 고유·외부 알림 전달 검증은 그 증거에 포함하지 않는다.

초기 Draft 생성 시점의 기록은 다음과 같다.

> 이 문단의 ADR-0036은 아직 미병합인 app lane 문서다. 로컬 `debt/product-lane`, worktree `/private/tmp/peakcart-worker-a-product`, 기준 commit `7607c0ac35525311fc9e9ab3908aa1f4acd60817`에 있다. Saga PR은 이 ADR 구현을 포함하지 않으며 consistency precheck의 참조 누락 경고를 숨기지 않는다. app 통합 전에는 원격 고정 링크를 만들지 않고, 필수 CI guard가 의존관계 불일치를 지적하면 통합 순서와 문서를 맞춘 뒤 재검증한다.

후속 로컬 통합 `8928ed4a6e116855d923cb17b0f0eca0ccbca5c9`에는 app 구현과 ADR-0036·0037 및 복구 ADR-0040이 포함되어 위 참조 누락은 해소되었다. ADR-0036·0037은 Accepted다. ADR-0040은 이 로컬 통합 당시 Proposed였고, 이후 PR181의 main 검증에 따라 서버 직접 확정 조회 복구 범위만 Accepted로 전환되었다. 영구 404 신뢰 증빙과 D084 전체 완료는 여전히 미해결이다. 이 상태는 아직 원격 PR180에 push하지 않은 준비 상태이며 최신 통합 head의 전체 CI·독립 검증·병합이 완료되었다는 뜻이 아니다.

D-075는 목표 처리율·backlog 수용 기준에 대해 사용자가 보류를 선택했다. D-084는 분리된 승인자/실행자, 감사, 서버가 직접 확인한 PG 확정 결과와 예약 대사 경로로 진행한다. 신뢰할 PG 무과금 증빙 원천이 없는 영구 404 강제 해제는 미완으로 유지한다. 운영자 업로드나 해시, 시간 경과만으로 무과금을 인정하지 않는다. 따라서 D-084의 일부 경로를 구현해도 전체 부채 완료를 뜻하지 않는다.

초기 main의 payment 이미지 publish가 unknown blob으로 실패해 진단 PR #179를 별도 검증 후 병합했다. 병합 SHA는 `3e1ba64c715d22b1f5a2d2635a97da17eb4ebb28`이며 main CI38981041166의 gate와 모든 publish가 성공해야 일반 부채 병합을 재개한다. PR의 조건부 publish skip은 이 증거를 대체하지 않는다. 상세한 head/base/checkout, artifact 및 실행 소유권은 audit에 보존한다.
