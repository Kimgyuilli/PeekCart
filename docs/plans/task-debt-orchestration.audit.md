# task-debt-orchestration audit

## 2026-10-17 — 상위 실행 계획

- 상태: 계획 작성. 구현·테스트·CI 실행·PR 생성·병합 없음.
- 기준: main `92755566074fc28d78e53dd84b2b085a7fbfdb38`.
- 사용자 결정 반영: root + 작업자 2 + 독립 PR 검증자 1, 작업자 sync/plan/work/ship, root 병합/기록, GitHub Actions 우선, 과금 작업 제외.
- 코드 확인: 열린 부채 20건, SharedContainers/cleanup, 상품 캐시·트랜잭션, Kafka backoff, saga E2E, Compose 격리/자원, CI shard/gate/이미지/증적, ADR-0030/0035, workflow 스킬.
- 분석 위임: resource_plan은 자원/격리, debt_plan은 부채 의존성·외부 게이트를 읽기 전용 조사. PR 리뷰가 아니며 승인/통과 판정 없음.
- 별도 Codex 리뷰 미호출. 향후 독립 PR 서브에이전트 검증과 기존 codex exec 리뷰는 구분해서 기록한다.
- 범위: 대상 16건, 조건부 D-063, 제외 D-064/065/078. D-064는 비용 미확인에 따른 보수적 제외이며 모든 PG 테스트가 유료라는 주장이 아님.
- 의도적 workflow 조정: 전체 검증은 무료 CI 증적으로 대체 가능; CI 기동을 위한 검증 대기 Draft PR 허용; 완료 기록은 root에 위임. 검증 대기를 완료로 표시하지 않는다.
- 미확인: 호스트/VM 자원, 원격 branch protection·무료 quota·저장소 가시성. sysctl 권한 제한 및 앞선 GitHub API 연결 실패. 실행 전 확인 게이트로 남김.
- 다음 단계: 사용자가 실행을 요청한 세션에서 P1부터 진행. 이번 문서는 이후 자동 실행을 시작하지 않는다.

## 2026-10-17 — 실행 P1 착수

- 이 문서의 이전 계획 전용 제한은 사용자의 이번 실행 승인으로 대체됨. 구현·검증·커밋·push·PR·병합·기록 승인, 무과금 조건 유지.
- 원격 fetch 후 main/origin/main `92755566074fc28d78e53dd84b2b085a7fbfdb38`, 열린 PR 0. 원래 작업 트리는 본 계획/audit 두 untracked 파일뿐.
- 보호: main strict required check `gate` (app 15368), enforce_admins=true, force push/deletion=false.
- 최신 main CI https://github.com/Kimgyuilli/PeekCart/actions/runs/38973369532: 필수 gate와 선행 전부 성공, publish(payment-service)의 docker push `unknown blob` 실패. 다음 병합 보류; 원인 조사 중. PR #176 run 38971753781 green은 main publish 성공 증거가 아님.
- 무료 확인: PUBLIC, workflow ubuntu-latest standard runner. 공식 Actions billing 문서와 Packages billing 문서 확인(https://docs.github.com/en/billing/concepts/product-billing/github-actions , https://docs.github.com/en/billing/concepts/product-billing/github-packages). GHCR storage/bandwidth 현재 무료. cache usage 11,209,959,444 bytes / 621개. cache/storage-limit API 402(payment method 필요), user billing usage API 404(user scope 필요). cache 설정/과금·artifact 무료 조건 확정 전 신규 CI 유발 작업은 보류. 결제/예산/auth scope 변경 없음.
- 호스트 8 CPU / 16 GiB, Docker 8 CPU / 8,217,341,952 bytes, 실행 컨테이너 0. 기존 사용자 Gradle daemon PID 4627 보존. heavy 슬롯 1개, worker A의 D-083 집중 테스트만 승인(직렬 옵션 필수).
- 역할: root=최종 기록/병합, worker_a=D-083/068/080, worker_b=D-073/077, verifier=구현 비참여 독립검증/기존 main 실패 읽기전용 분석. 중첩 에이전트 없음.
- worktree: A `/private/tmp/peakcart-worker-a` branch `debt/foundation`; B `/private/tmp/peakcart-worker-b` branch `debt/documentation`; verifier `/private/tmp/peakcart-verifier` detached 기준 main. 공통 base는 위 SHA.
- 파일 소유: A common 테스트 fixture/회귀·MySQL Compose/k8s/lint·개별 계획, B D-073/077 관련 Layer1/swagger/javadoc/과거 증거 정정 추기/개별 계획. root 현행 TASKS/PHASE5/ADR index/상위 계획/audit. 완료 표시 없음.
- 별도 codex exec 리뷰 미호출; 독립 PR 서브에이전트 검증은 PR 생성 전이므로 미실시. 기존 CI 실패 분석은 PR 승인으로 세지 않음.

### P1 추가 증거와 현재 차단

- main 실패 독립 조사: PR #176 head `cff7c0cd87585dd11759d3e0d64706f80fd7d273`, base `cb8056d289ccaae483b878ee4ed9fafa7eb1bfa3`. main run attempt1 실제 checkout은 `92755566074fc28d78e53dd84b2b085a7fbfdb38`.
- image-payment-service artifact `12077669618` checksum/image identity 검증은 성공. Docker28.0.4 overlay2. GHCR에서 실패 SHA 태그는 404 MANIFEST_UNKNOWN, latest는 이전 main cb8056d 태그와 동일한 `sha256:e1daf0e24b5682a1a4f4814b504a0a8369290e610749b5c2249ce9c064756c14`, 기존 config/9 layer blob HEAD는 모두200. 새 payment 이미지 미게시, 이전 이미지 보존.
- push stdout가 command substitution에 잡힌 상태에서 set -e로 종료하여 상세 실패 단계가 유실됨. root cause가 registry의 어느 blob 동작인지 미확정. 무원인 CI 재실행 없음. 진단 stdout/stderr 보존 보강 후 원인 분리 필요.
- https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching 의 기본10GB save-then-evict 특성 때문에 10GiB 초과 관측만으로 과금이라고 단정하지 않음. 유료 확장에는 payment method와 opt-in 필요. 무료 설정 확인은 사용자에게 비동기로 요청했으며, 응답을 임의 승인으로 간주하지 않음.
- D-083 worker A 집중 테스트 4건 성공(11s), wrapper lock sandbox 오류 후 승인된 동일 명령 실행. heavy 슬롯 반환, 본 세션 생성 컨테이너 없음. 전체 CI/독립 PR 검증/병합은 아직 없음.
- 완료 계획서 dry-run: 기존 PR175/176의 D-060, D-060-followup, D-074, D-079, D-081, D-082 이동 후보. 기존 main publish 실패를 닫기 전 완료 기록 자동 이동 보류.

### 정책 결정 대기 (구현 전 독립 위험 분석)

- D-069: 기존 M7은 동시성/SLO 선결, 정상 GKE p99≤100ms를 장애 환경의 수용 기준으로 재사용할 수 없음. 권장: DB 진입 bulkhead + 초과 즉시503/대기0, pool의 쓰기·기타조회 여유와 로컬 측정으로 상한 산정. 사용자에게 장애 시 일부 요청 거절 허용 질문, 결정 전 정책 구현 보류.
- D-075: 기존 명세에 목표TPS/최대 backlog age가 없음. 무료 로컬 병목 분리와 정합성 실험은 진행 가능, 수치 목표 또는 현 상태 수용은 사용자 결정 대기. 임의 성능 목표/완료 선언 금지.
- D-084: ADR0034/35는 시간·404·단순 운영 확인으로 보호 해제 금지. 현재 resolvedBy 문자열은 인증 증거가 아님. 권장: PG 발급 대사 증빙+예약/환불 대사, 승인/실행 역할 분리, 감사 불변 기록. 신뢰 증빙 출처·권한 주체는 사용자 결정 대기. 과거 FAILED/MANUALLY_RESOLVED 행의 무조건 재개/실패이벤트 재발행은 허용하지 않음. generation/행잠금·PG성공→환불·Product CAS 경합 검증 필요.
- D-071: root 통상 선택 — 장바구니 item은 보존하고 미수신 상품 metadata는 null/availability로 명시. 가짜 이름/0가격, 전체 장바구니 실패, 자동삭제 금지. 주문의 기존 ORD007/009 계약 유지. 이벤트 역전/재처리는 기존 version 계약 준수.
- 위 중요 결정 질문은 비동기로 남겼으며 답변 부재를 승인으로 해석하지 않음. 다른 독립 구현 계속.

### 로컬 구현 큐 / 독립 검증 (PR 미생성)

- documentation `debt/documentation` head `866bbf2` (base9275556): D-073/D-077, 15파일 로컬 커밋. CI·PR·병합 없음. 독립 verifier 원본 JSONL command/result 직접 대조 성공(60s TPS, 별도70s CPU, 이후400VU).
- 독립 검증 수정요구: ADR0025 정정문의 '새 역전이 이동 뒤 생겼다'는 의도 반대 문구 수정; 현행 docs/03-requirements.md Redis 직렬화/낙관락 최후방어 문구도 ADR0025와 정렬. worker B에게 반환. 통과로 기록하지 않음.
- D-073 블로그 원본 추가 발견: `/Users/kimgyuill/dev/projects/blog/src/content/blog/peekcart-28-ruled-out-bottleneck.mdx`, 로드맵27편과 실제28편 대응 검토. 외부 저장소는 읽기만 수행하며 수정/게시 없음.
- 다음 B worktree `/private/tmp/peakcart-worker-b-messaging`, branch `debt/messaging` base9275556, D-076→D-071. ADR0036은 D-071 필요시 예약. 문서 검증 수정이 우선.
- D-063 동등성: ADR0015는 실제 Prometheus/Grafana 평가를 요구하며 GKE 고유 불변식은 아님. 로컬 정본 provisioning·실제 scrape·Normal→Pending→Alerting→Normal, 원본 for(15m/10m/5m)를 유지하면 규칙 평가 범위 충족 가능. NoData를 resolved로 오인 금지, 값0 복귀와 동일 instance Normal 확인. GKE ServiceMonitor/운영 series/외부 알림 전달은 이 검증으로 주장하지 않음. D-070 뒤 실제 실행 예정이며 아직 검증하지 않음.

- 로컬 root 기록 commit `9f860e7`, branch `debt/orchestration-records`. 계획/audit는 이제 git에 보존됨. 원격 push 없음.
- foundation head `fa2cf77`: D-083/D-068 및 main publish 로그 보존 진단 보강을 별도 커밋으로 보존. GHCR 원인 해결을 주장하지 않음. D-080 원본 shuffle 증거는 추가 조사 중.
- heavy 슬롯 A에 D-068 `./gradlew :order-service:test --tests com.peekcart.order.infrastructure.OrderCursorQueryPlanTest --no-parallel --max-workers=1 --no-daemon` 승인. MySQL-only 집중 통합, 실제 결과는 종료 후 기록.

- documentation 최신 `37b6342`: 독립 지적 ADR0025 반대 의미 문장·현행 requirements·roadmap 요약 수정. D-073 블로그 원본(실제28편/로드맵27편) 114–120/314/452행 대조로 원본 부재 차단 해소. 구현 기록의 완료는 로컬 구현만 뜻하며 CI/PR/병합 미충족 유지. 독립 재검증 배정.

- foundation `fa2cf7731bfa46d26dbe112fcfff7506ca6e94d8` 독립 로컬 검증: D-083 실제helper 4반례/worker JUnit4·0·0 대조, MySQL guard+27 음성 self-test, publish 실패 probe exit17/출력보존/승격차단 및 releaseguard24/24 통과. D-080은 제외. PR head/base/CI checkout 검증이 아니므로 병합 승인 아님.
- D-068 heavy 실행: 2026-10-16T22:44:59Z, workerA session8962, 위 SHA + D-080 주석·문서 미커밋(실행코드 불변), `.cache/debt-orchestration/D-068-query-plan.log`. 슬롯 A 소유, 종료 확인 전 재할당 금지.

- documentation 최종 `b2a090db81c41dd5b9a1c6653f2e696593b31666`: 독립 로컬 source/evidence PASS. MySQL만 상한 미조작이고 product CPU는1000→3000 조작한 주어 구분까지 수정. D-073 원본대기 해소. D-073/D-077 CI/PR/병합·done은 대기.
- D-076 실제handler retry/recoverer 단위테스트 heavy 큐1, A MySQL 실행 종료·컨테이너 정리 확인 후 배정. 동시 heavy 실행 없음.

### 사용자 후속 결정 (2026-10-17)

- 무료 CI 확인 질문(artifact/cache 무료 범위 및 유료 cache 확대 꺼짐)에 `네 문제 없습니다` 응답. 사용자 계정 확인과 PUBLIC/standard runner/공식 GHCR무료 근거를 합쳐 원격 CI 실행 보류 해제. 예산/결제/한도 변경 없음. 최초 active PR CI는 foundation+documentation 두 개로 제한.
- D-069 `DB 보호·초과 요청 503 허용` 승인. 실제 pool 여유·로컬 실측 기반 동시성 상한 설계 진행.
- D-075 `보류` 결정. 목표TPS/허용backlog 수용 판단 미결, 완료 처리 금지. 별도 재개 결정 전 성능정책 변경 보류.
- D-084 `권장안대로 진행합니다` 승인. PG 발급 대사 증빙·예약/환불 대사·승인자/실행자 분리·감사 기록 필수 정책으로 설계/구현 진행. 운영/실PG 실행 제외는 유지.
- D-068 집중 통합4건 실패0/오류0, XML24.927s, Gradle1m, seed1792190712260. A session8962 종료; MySQL/Ryuk 정리 뒤 docker ps0, 기존 사용자 Gradle PID4627만 유지. 슬롯 반환.
- heavy 슬롯 B에 D-076 실제handler 집중 단위검증 승인, no-parallel/max-workers1/no-daemon/offline. A 동시실행 없음.

### PR 큐와 main 복구 순서

- 자동승인 검토가 PeakCart/PeekCart 이름 차이 및 구체 payload 확인을 요구하여 첫 push 거절. 읽기전용 API로 두 경로 모두 repository ID1187970015/full_name Kimgyuilli/PeekCart, origin clone_url 일치 확인. 문서17파일/source설명 및 foundation의 승인된 diff 확인 뒤 재검토 승인, 우회 없음.
- foundation PR https://github.com/Kimgyuilli/PeekCart/pull/177 , head `e0dcd01c0f65e52dcf31b8d358c95f4e996c093f`, base9275556, Draft, CI https://github.com/Kimgyuilli/PeekCart/actions/runs/38977855850 진행중. D-083/068 구현·집중검증, D-080 부분정정(셔플원본 근거대기), main publish 진단 포함. 완료로 세지 않음.
- main 실패 복구 우선: 작업자 A의 진단커밋82d3e4c/fa2cf77만 root가 `/private/tmp/peakcart-publish-diagnostics`, branch `debt/publish-diagnostics`, head392901c(base9275556)로 cherry-pick. 코드 작성자는 A, PR 작성도 A에 유지; root는 병합만 담당. CI슬롯 확보 후 별도 PR/검증, 복구 목적 진단 변경 우선 병합 후 main 결과 확인. 일반 부채 병합은 그때까지 보류.
- A 다음 lane `/private/tmp/peakcart-worker-a-product`, branch `debt/product-lane`, base e0dcd01(미병합foundation). D-067→066→072→069 진행. ADR0037 예약(필요시0038/0039); B의 D-071 ADR0036과 구분. base변경 때 검증 갱신 필수.
- D-076 B focused 최초compile 실패는 Order handler 생성자 인수 누락, 수정 뒤 실제handler 테스트4건 PASS(28s), session75678 exit0. Docker/잔여프로세스 없음, 슬롯 반환. 미원인 반복재실행 아님.

- documentation PR https://github.com/Kimgyuilli/PeekCart/pull/178 Draft, head `b2a090db81c41dd5b9a1c6653f2e696593b31666`, base9275556. CI https://github.com/Kimgyuilli/PeekCart/actions/runs/38977913185 실행 중. 최초2CI(#177/#178) 활성, 진단PR는 슬롯대기.
- B messaging 로컬 head `af17542`, D-076 구현/집중4tests PASS, D-071 계획/ADR0036 및 구현진행. 원격 PR아직 없음.

- #177 독립 검증 진행: 실제 guards checkout `e094905ebd2cb61b5199d869ceebc050c5e3a7af`, 부모9275556+e0dcd01 확인. D-080 원본174XML hash/name/time/testcount 대조 및 CI58개131.202s·마지막13:21:14.693 재산출, E1log/jstack3hash·실제jar CacheKey/identity equals 대조 통과. 셔플 P3 열림 유지 확인. 현재 root-reports만 존재, 전체CI/최종승인 아직 대기.
- 분리 diagnostics392901c 2파일 로컬 코드 독립 PASS, PR/CI대기.

- D-080 원본 차단 해소 조사: verifier가 `~/.claude/projects/-Users-kimgyuill-dev-projects-PeakCart/bf37ea21-0531-4800-8ebf-4ee2fcc96d3b.jsonl`에서 순차run1(1281/1287), run3(1382/1386), run5(1464/1465) 실패와 이후 셔플 최초도입1592행 발견. A에 원본근거 추가정정 배정. 현재#177 CI는 유지하고 진단복구 병합 후 base갱신과 함께 최신수정 재검증 예정. 아직 문서/PR 수정·완료된 것으로 세지 않음.
- 복구 우선순위 때문에 진단PR 생성 시 미완PR가 일시3개가 되는 예외를 허용한다. 일반 구현PR 추가는 보류하고 활성CI는 계속 최대2개. 기존 main 실패 해소를 위해 진단PR만 우선 처리한 뒤 일반 큐로 복귀한다.

- PR138 원격 본문도 verifier 읽기검증:78행 '순차실행에서한번도보이지않던것들',85행 결함4에 실제순차실패 반례 있음. 원문보존 정정추기 필요. 다른3결함의 최초발견까지 일괄부정하지 않음. A가 정정안 준비, 원격수정전 내용확인 예정.
- 로컬 heavy 슬롯 B D-071에 승인, foundation e0dcd01을 먼저 병합하여 고정MySQL/cleanup기반에서 검증. A D-067은 대기큐. D-071은 actual MySQL/Kafka/Redis 테스트 포함, 컨테이너정리까지 확인후 반환.

- D-071 heavy 시작: workerB messaging head `a3ed06892d066715cfe756691c91eca04a05eb35` (foundation e0dcd01 merge, 선행PR미병합), session87257, `/private/tmp/peakcart-d071-test.log`. 시작 docker ps0, 테스트중코드고정. A D067대기.
