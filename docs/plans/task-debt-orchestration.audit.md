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

- #178 실제guards checkout `075c87f17e7a92c06cb8e848f635ca49dfec49fa`, 부모9275556+b2a090d 확인. #177 checkout과 혼용하지 않음. 두 PR 모두 최종gate대기.
- D-071 첫 focused24건 중23PASS(실제MySQL metadata4/Saga7 포함), reader unit 기대값의 Boolean삼항식 null언박싱 NPE1. 구현switch와 별개 기대값수정후 실패클래스 재검증 session33764. 첫 실행을 전체green으로 기록하지 않음.
- B 다음 worktree `/private/tmp/peakcart-worker-b-monitoring`, branch `debt/monitoring`, base9275556. D-070/D-063 정본alert/guard/runbook/무료로컬실제평가하네스 배정. D-075보류 유지.
- root 문서통합 대기: D-076 docs04 §8-3 fixed sequence 설명을 기준1/5/30초 ±50%jitter로 정렬, ADR0036 인덱스 추가. 작업자간 파일소유충돌 회피를 위해 root후속기록으로 모음.

- messaging `07a14e5` 로컬clean 고정, D-071 수정후 실패클래스3PASS(15s), session33764 exit0, 실제DB11PASS와 합성증거 구분. docker ps0으로 MySQL/Redis/Kafka/Ryuk 정리확인, 슬롯반환. 독립검증큐 배정.
- heavy 슬롯 A D-067 집중회귀: 22:53:59Z, basee0dcd01+상품lane미커밋, session14434, `.cache/debt-orchestration/D-067-focused.log`. 테스트중소스수정중지. product MVC/5controller tasks별필터/직렬옵션.
- D-080 local foundation `a559fde` 추가정정(원격PR177은e0dcd01유지), 순차원본발견이력/README/과거plan·audit/PHASE5/TASKS-done/ADR28 원문보존 정정추기. 독립검증 배정. PR138본문 추가정정 초안은 수치/서술 확인후 반영 예정, 아직원격수정없음.

- D-080 a559fde 로컬 원본정정 독립PASS, PR138추기안 수치/범위PASS. 기존본문보존하며 foundation최신head push시 고정근거링크와 함께 추기 예정.
- D-071 독립검증P1: applicationDTO에 추가한4필드가 CartItemResponse.from에서 버려져 실제GET/POST/PUT HTTP응답에 나오지 않음. 기존serializer검증은applicationDTO만확인한false-green. B에매핑수정+실제HTTP회귀 반환, 통과/완료표시금지. D-076은독립소스검토PASS(CI미검증).
- #177/#178 모듈test/guards/images통과후 E2E scenarios/negative-control 진행중. 최종gate/artifact승인대기.

- D-067 session14434 종료0, Gradle48s, product16/order43/payment10 총69실패0. docker ps0, 슬롯반환.
- D-071 B actualHTTP known/null GET·POST·PUT 6사례 WebMvc 회귀 슬롯승인. 기존내부DTO검증불충분을audit에명시하고 계획검증항목재개방. 끝나면A상품실제DB회귀큐.
- D-069 기본동시1은SLO로주장하지않고보수적설계선택이라는A설명을검토중. 실제Hikari pool여유·쓰기예산·동시요청DB진입측정근거보강요청. 무근거성능목표로완료선언금지.

- D-071 actualHTTP GET/POST/PUT known/null6건 PASS(37s), session86078exit0/dockerps0. messaging최신 `a777799` clean, 독립P1수정재검증배정. 슬롯반환.
- A 상품lane최신 `68f8d48` clean, ADR0037. D-066/072/069 실제MySQL/Redis/Kafka/Toxiproxy 회귀5클래스(ProductQueryServiceTest, ProductReadBulkheadConfigTest, ProductCacheIntegrationTest, ProductDetailQueryCostIntegrationTest, ProductCacheFallbackIntegrationTest) heavy슬롯승인. pool계수/blocked DBread옆쓰기성공·cachehit우회반례추가, 결과대기.

- 상품 lane 독립1차검토: D-067 입력400/반환500, D-072 cache→bulkhead→transaction 및서버general_log 양성대조, D-069 finally permit반환/503/다른UPDATE연결여유검증 타당. default1은최소양의DB보호정책으로명시했고SLO측정치로주장하지않음. D-066 priceASC/default 위주테스트는불충분하여nameASC/DESC/idDESC·동률/페이지경계·모든방향cache격리·duplicate/ignoreCase/nullHandling 반례보강요구. 완료승인아님.
- A Saga worktree `/private/tmp/peakcart-worker-a-saga`, branch `debt/saga-evidence`, base9275556. D-061/062와외부정지/재기동오케스트레이션 `scripts/saga-e2e-smoke.sh` 소유. 중간실패정리·후속시나리오무간섭필수.

- 상품 lane26건실제회귀 PASS(Gradle1m22s), session49386종료/dockerps0. Hikari maximum10 active1 idle9 및별도UPDATE성공, Redis차단4동시HTTP503/repo진입1/회복/cachehit우회. D-072 MySQLgeneral_log hit0/miss>0/advisor순서통과. D-066 보강검증은별도진행중.
- D-066 보강2클래스 session35309, 23:00:42Z, head68f8d48+test2/문서미커밋, `.cache/debt-orchestration/D-066-strengthened.log`, 실행코드고정.
- monitoring `b47bd1b` clean: D-070 PromQL/SSOT/44변이selftest PASS. D-063 하네스실행전 독립계약PASS(정본전체byte/hash/provision,for/1m간격,실제exporter2,잘못된label음성,짧은breach/18m지속/0값복귀,NoData거절,소유project정리). 실제Grafana호환/발화상태는아직검증안함. A슬롯반환후35분무료로컬검증예정.

- D-066 보강 session35309 종료0/35s/11tests0실패, dockerps0. 허용정렬6종·각양방향·동률·2페이지·옵션거부검증. A슬롯반환, 최종commit/독립재검증예정.
- heavy 슬롯 B D-063 실제35분monitoring 승인: SHA b47bd1b(clean), `python3 scripts/monitoring/verify_alerts.py --output /private/tmp/peakcart-d063-20261017-run1 --run`, log `/private/tmp/peakcart-d063-20261017-run1.log`. 고유project4containers(loopback동적포트), finally logs/down-volumes/소유ps0검사. 다른heavy금지.
- B 복구설계 worktree `/private/tmp/peakcart-worker-b-recovery`, branch `debt/approval-recovery`, base9275556. ADR0040예약. 사용자승인정책을기존인증principal/대사증빙/승인실행분리/감사와과거FAILED·generation·재고CAS경합조건으로구체화후root설계검토. 우선읽기/계획, 실험중다른heavy실행안함.
- D-061 UNRESOLVED는생산자가회신미발행하는ADR계약유지: 실제Payment원장UNRESOLVED/outbox0 관측과consumer3곳합성회신무전이/idempotency검증을구분. SUCCEEDED/FAILED는실제dispatcher회신. 합성검증을실제생산chain으로주장하지않음.

- 상품최종 `f3a311f6ce3861d398ede9ca697fbf83817a467b` clean, 독립로컬sourcePASS. D-066 정렬6종양방향/2페이지/금지옵션11testXML직접대조. ADR0026물리커넥션해석/현행정렬·503문서검토PASS. 전체PR/CI대기.
- D-063 시작 session55392, project `peakcart-alert-1792191745`, SHA `b47bd1bffe771208e17c7279bdc1ebc7ab3bbe64` clean, rulehash3c453153..., evidence `/private/tmp/peakcart-d063-20261017-run1`, 시작dockerps0. B슬롯독점, 현재pull/startup.
- #177 run38977855850 SUCCESS완료(root실조회), 원격e0dcd01기준. verifier최종artifact/실제checkout대조배정. 새로컬a559fde정정에는그green을사용하지않음.
- CI슬롯1개반환으로 A에 diagnostics392901c push/DraftPR생성승인. main실패복구용우선, 일반PR병합보류유지.

### 통합 큐 조정

순차병합마다 strict base가 바뀌어 같은전체CI를반복하는비용/시간을줄이기위해, root가서로검토된작업을묶어최신head에서다시검증하도록조정했다. 검증생략아님.
- 첫묶음: #177에 documentation b2a090d를합쳐 D-083/068/080/073/077. A가local통합준비, 진단main복구후최신base와함께push하여전체CI/독립검증. #178은변경포함확인뒤통합대체로닫고완료로세지않음. 원래branch/증적보존.
- 다음app묶음: product f3a311f와messaging a777799 통합검토예정. 개별계획/검증/담당은유지하고합쳐진코드의전체CI를새로확인한다.
- 진단PR우선순위/activeCI최대2/heavy슬롯1/독립검증/root만PR병합 원칙유지.

- 진단PR https://github.com/Kimgyuilli/PeekCart/pull/179 , head `392901c7134937e6a8a98566756b6221a1fbc291`, CI https://github.com/Kimgyuilli/PeekCart/actions/runs/38979426567 진행중.
- #178 run38977913185 SUCCESS완료(root실조회), 독립artifact최종대조/통합대체대기.
- foundation local 통합 `5466a4c653b1101902a5d38af1176bc1a1dde7cf` clean, documentation b2a090d포함. TASKS-done 유일충돌은 D073/D080정정양쪽보존. 분류별원본commit을유지하는merge snapshot의mixed hook예외(HPX_ALLOW_MIXED=1), 테스트/CI/보호우회아님. push아직없음.
- #177 원격e0dcd01/base927 독립CI PASS: actualcheckout e094905, 필수17job+gate, XML229개/1296tests failure/error/skip0, queryplan4, E2Ea–e동일checkoutsuccess, artifact4checksum/imageID검증load, negative-control실제주입검출. PR조건publish skip은mainpublish증거로세지않음. 로컬새head/base에는이green적용불가.
- D-063 run1 기동실패: Grafana11.6가정본 `peekcart-scrape-absent-notification-service` UID41자(max40)에서종료. 평가0건, 성공증거없음. B가UID/전체참조정정·길이lint/음성반례·old/new mapping준비, expr/for/labels보존. cleanup확인후새SHA독립검토/run2승인순서.
- D-084 신뢰원천미결추가: signedADMIN역할+분리allowlist/감사는가능하나PG발급무과금증빙검증형식/공개키/공식대사원천이없음. 사용자에게구체원천질문. 그전404/운영자업로드hash만으로해제금지. 서버직접PG확정결과복구는진행가능하나영구404해제는미완유지.

- D-063 run1 session55392 exit1, GrafanaUID기동실패/API180초timeout/observations0. finally cleanup후 소유project `peakcart-alert-1792191745` ps-a0확인. startup/containers/cleanup로그보존, B슬롯반환. 원인수정/독립검토전재실행없음.

- #178 최종독립CI PASS: b2a090d/base927/actual075c87f, 필수17jobs, XML228/1292tests 실패·skip0, E2Ea–e exactSHA/success·4imagechecksum/ID·negative-control. 통합후새head/base검증으로대체필요.
- D-063 수정68dc697 독립재검토에서추가결함: REQUIRED_UIDS만새UID이고 ALERT_EXPR_CONTRACTS생성은옛UID라 notification식정확검증우회. run2보류, 공통mapping적용과0*식음성반례추가반환. selftest45PASS만으로이누락을통과시키지않음.
- D-084 직접관련안전조사: 기존 ApprovalExecutor TERMINAL_NEGATIVE의PARTIAL_CANCELED가잔여과금미확정인데해제로흐를위험. 범위내안전분류/회귀보강승인. Product대사는Payment직접DB/동기호출을추가하지않고기존이벤트request/reply·outbox/inbox·generation/finalCAS로설계하도록지시, ADR0040에서배포/DLQ/replay/경합조건확인후구현.

- D-063 최종원인수정 `c406204c9cede0ea36c5eac0761c78c2949a4ad8` 실행전독립PASS. 공통UID함수로expr/required일치, 정본lint0/selftest46직접PASS, 동일notification0*독립반례exit1확인. B run2승인(새output/project, run1증거보존), 실제상태평가아직대기.
- #179 CI38979426567 test/guards/images통과후E2E두모드진행중. 최종gate미완료.
- D-084 설계검토: 이벤트대사·분리ADMINallowlist방향수용. plan/ADR정규형식, 실행시PG재확인/staleapproval거부, 늦은DONE안전환불/확정경로, 감사원문민감정보로그제한, 정확상태머신/DDL/배포계약을추가확정하도록반환. 404외부증빙원천미결유지.

- D-063 run2 시작 session80307, project `peakcart-alert-1792192171`, SHA c406204 clean, evidence `/private/tmp/peakcart-d063-20261017-run2` 및log. Prometheus3.2.1/Grafana11.6 health/provision통과, imageidentity기록, rulehash `b8effdda5853605f47bc9b13db2c9006bdaf35893c06a5c72bdc99266c38a09e`. B슬롯독점, baseline평가진행.
- 통합app `d88c6392be889718e13873de08a648463eac01a8` clean/no push: productf3+foundation5466+messaginga777 무충돌병합. HTTPmetadata6/common400회귀보존, 원본분류commit유지. 독립통합검토큐.
- Saga최종 `8e68b43` (구현7d1f436) clean, syntax/33selftest/matrix35구조·selftest/consistency통과. CI45분상한유지/D480s/F600s, 실제E2E/durations증거는대기. 독립하네스검토큐.
- D-084 request/snapshot/execute/result4event 및Product version/fenceCAS 설계중. 기존reconciler가비동기결과대기를재claim하지않도록 RECOVERY_PENDING 후보제외상태검토. API/metrics/scheduler/inbound스윕과nativeCAS/@Version실제DB검증필수. Product예약관련파일소유B, Aquery/cache·Saga와구분. 구현전정확프로토콜확정중.

- foundation root기록통합후 `8cc47c52daae0f19065d8fc25abfc24b59ae358a` clean/no push. 상위계획/audit2파일추가, 충돌없음.
- app최신 `7607c0ac35525311fc9e9ab3908aa1f4acd60817` clean/no push. root문서정합성으로 docs04jitter설명/ADR0036·0037인덱스를각분류commit으로보존. 통합코드 d88c639독립PASS는유지하며새문서추가검토대기.
- Saga최신 `538fec4` OffsetAndMetadata2필드호출정정: 고정kafka-python-ng2.2.3공식wheel/hash/API읽기대조, 33selftest재PASS, 독립작은diffPASS. 실제broker/E2E는아직대기.
- D-063 run2 baseline정상후 transient Pending 관측, 정상값복귀관측구간진행. 전체발화/복구통과아직주장하지않음.

- 사용자 D-084 신뢰원천질문에 `권장하는 방안으로 진행해주세요` 응답. 질문에명시한보수적방안(서버직접PG확정결과복구만구현,404/업로드hash강제해제금지,영구404는미완유지)으로확정. 외부증빙원천을임의로승인받았다고해석하지않음. D-084전체완료표시금지, 가능한안전복구부분은계속구현.
- Saga+monitoring 통합 `26b5af20b5e07dbf8439b7c91278300a381a1bf2` clean/no push, c406204무충돌병합·관측lint/diffcheckPASS. B실험원본worktree미접촉, D-063실제검증대기. 후속검증PR큐.

## 현재 실행 체크포인트 (2026-10-17, 병합 전)

| 담당 | 작업 | branch / worktree | 현재 근거와 남은 조건 |
|---|---|---|---|
| A | D-083/068/080/073/077 | debt/foundation / /private/tmp/peakcart-worker-a | local8cc47c5에 문서와 root audit 통합. 원격#177 e0dcd01 CI·독립PASS, #178 b2a090d도PASS. 새통합head는 진단복구 후 base갱신/CI 필요 |
| A | main publish 진단 | debt/publish-diagnostics / /private/tmp/peakcart-publish-diagnostics | #179 head392901c, CI38979426567 E2E진행. root만 검증 후 우선병합; main 성공 확인 전 일반병합보류 |
| A | D-067/066/072/069/076/071 | debt/product-lane / /private/tmp/peakcart-worker-a-product | 통합7607c0a, 개별실제DB/HTTP/장애회귀 및 독립로컬PASS. PR/최신base 전체CI 필요 |
| A | D-061/062/070/063 | debt/saga-evidence / /private/tmp/peakcart-worker-a-saga | 통합26b5af2. Saga/경보 정적·독립PASS, 실제E2E는CI대기. D063 별도실험진행 |
| B | D-063 실제 평가 | debt/monitoring / /private/tmp/peakcart-worker-b-monitoring | c406204, run2 session80307/projectpeakcart-alert-1792192171. /private/tmp/peakcart-d063-20261017-run2에 증거. heavy독점, 끝나면 소유컨테이너 정리 확인 |
| B | D-084 안전복구 | debt/approval-recovery / /private/tmp/peakcart-worker-b-recovery | ADR0040·이벤트/권한/감사/경합 구현중. PG확정결과 경로만 승인, 영구404 외부증빙은 미완 유지 |
| root | 기록/최종병합 | debt/orchestration-records / 원래저장소 | 현행TASKS/PHASE/ADR정합·done은 병합/main검증 뒤. 아직 이번부채 완료표시0 |
| verifier | 모든 PR 독립검증 | /private/tmp/peakcart-verifier | 구현참여없음. 최신head/base/checkout/gate/artifact 재확인. codex exec 미호출 |

D-075는 사용자 보류. D-064/D-065/D-078은 제외·열림 유지. 현재 무거운 로컬 실행은 B run2 하나뿐이며, A와 verifier는 경량 작업만 한다. 다른 사용자의 기존 Gradle daemon PID4627은 건드리지 않는다.

다음 순서: #179 최신CI/독립증거 확인 → root 복구목적 병합 → main push(gate와publish) 확인 → foundation 최신main 통합/push 및 #178 통합대체 정리 → 다음 통합PR 검증. 코드/base 변경 전의 green을 새SHA에 사용하지 않는다.

## 복구 진단 PR 병합 (2026-10-17)

- #179 head `392901c7134937e6a8a98566756b6221a1fbc291` / base `92755566074fc28d78e53dd84b2b085a7fbfdb38` 독립 최종 PASS. 실제 checkout `f2c01383b482bcdedf0eb48b969abb4333b82df9` 부모 대조, CI38979426567 필수17jobs/gate 성공, XML228/1292tests 실패·오류·skip0, 양 E2E 이미지 checksum/ID와 동일SHA A–E 및 음성 대조군 확인, artifact20개 만료0.
- root가 PR 본문에 최종 증거를 기록하고 ready 후 정확 head 조건으로 정상 merge. 보호 우회/force push 없음. 병합 SHA `3e1ba64c715d22b1f5a2d2635a97da17eb4ebb28`, 시각 2026-10-16T23:21:59Z. 복구 진단 목적이며 기존 unknown blob 원인 해결로 주장하지 않음.
- main push CI https://github.com/Kimgyuilli/PeekCart/actions/runs/38981041166 시작. gate와 6개 publish 성공 확인 전 일반 부채 병합 보류. verifier가 실제 checkout 및 배포 산출물 검증 담당.
- A는 대기 중 foundation을 새 main에 통합해 #177 최신 CI 실행 준비. 원격 포함 확인 후 #178을 통합 대체로 닫고 PR138 원문에 D080 정정을 append. 앞선 head의 green은 재사용하지 않음.
- D063 run2는 transient 전체 Normal 복귀를 통과한 뒤 sustained 관측 진행 중. heavy 슬롯은 B session80307이 계속 독점. 성공/완료 판정 아직 없음.
- Saga 최신544f983의 lingering F 등록 명시 가드 추가까지 독립 소스 확인. 실제 CI는 대기.

- foundation #177 최신 head `cb58da0944fc49ee14756f307328662f8bcca39a`, base3e1ba64, CI https://github.com/Kimgyuilli/PeekCart/actions/runs/38981159191 진행. D083/068/080/073/077 통합. 기존 검토5466 대비 소스는 상위 계획/audit 기록 추가와 이미 검토한 동일 publish 진단이며 독립 증분 확인; 실제 최신 CI 산출물은 아직 대기.
- #178은 원격177의 b2a090d 포함(behind0/ahead22) 확인 후 통합 대체로 CLOSED, mergedAt null. 병합 완료로 계산하지 않는다.
- PR138 본문은 기존 원문 prefix를 보존한 채 D080 정정 추기 완료. 셔플 전 순차 run1/3/5에서 결함4가 이미 발생한 사실과 약515초 종료 꼬리 구분, 원격 a559fde의 원본 증거 링크를 기록했다. 기존 기록을 덮어쓰지 않았다.
- app7607의 ADR 인덱스 및 jitter 문구도 독립 소스 대조 완료. Saga는 최신 main과 root835dd92 기록 통합 후 두 번째 Draft PR CI 준비 허용. 실제 D063 실험과 E2E가 미완이며 foundation 후 base 변경 시 재검증한다.
- D084 독립 초안 검토에서 strict DONE 후 두 번째 느슨한 재조회가 negative 실패해제로 우회할 수 있는 경로를 발견해 수정 반환. 응답 유실의 멱등 회수·유효 principal 거절 감사·명시 false 후 재대사 및 동일 payload 재전송의 generation/version 경합 조건을 보강 중. 아직 컴파일/실DB 검증 전이므로 안전복구 부분 완료도 주장하지 않는다.
- D075는 사용자 보류 이후 구현·원인분리 실험을 수행하지 않았다. 목표 처리율/backlog 수용 기준을 결정해야 재개할 수 있다.

- Saga/monitoring Draft https://github.com/Kimgyuilli/PeekCart/pull/180, head `e417b8a7f1d08e14fc27ed16738845b0c6612035`, base3e1ba64, CI https://github.com/Kimgyuilli/PeekCart/actions/runs/38981330710 진행. active PR CI는177/180 두 개. D063 실제 발화/복구 결과는 아직 대기.
- root 기록에 언급된 ADR0036은 미병합 app lane7607에만 존재해 Saga consistency 사전 검사에서 missing 경고. PR 본문에 공개했고 다음 base 갱신 때 로컬 문서 보완 ee9325c를 포함한다. 필수 guard 실패를 무시하지 않는다.
- 아카이브 실제 원격 조회 preview: 기존 #175/#176의 계획6건 이동 대상, 상위계획은177 미병합으로 진행중. sandbox 네트워크 없는 첫 조회의 UNKNOWN은 미병합 사실로 채택하지 않았다. main 복구 확인 뒤 이동/인덱스 갱신 예정.

- D084 소스296a82c의 독립 재검토에서 DONE 추가 조회 제거, 중복 ID/결과 회수, 분리 거절 감사 누락 해소 확인. generation/version을 보존하는 resume/retry 경로도 소스상 추가 차단점 없음. 실제 테스트는 미실행이라 최종 PASS가 아니다.
- D084 foundation cb58 통합 후 최신 `5d9e1bf909870dd538ee231d07717d41f8933d2c` clean. 현재 미병합 foundation 기반임을 명시. run2 종료/cleanup 이후 common topology/replay, Payment 복구/기존 승인, Product version/Saga 집중 실제 회귀 큐.
- B에게 D084의 Layer1 docs03 운영 API 절, docs05 복구/감사/stock version 및 RECOVERY_PENDING 설명, ADR index0040 Proposed 행 편집을 독점 위임. app lane의 ADR0036/37 및 기존 문구를 통합 시 보존한다.
- D063 run2에서 정본10m를 유지한 DLQ/get 발화도 관측. compensation15m 및 최종 실제 정상값 복귀는 대기. recovery branch의 경보 변경은 approval-stale 규칙 하나뿐이며 기존 D063 대상3개 규칙 전체동일 확인. 이후 D070 cache2 통합 뒤 대상5규칙 불변을 재확인해야 한다.

## 복구 main 검증 (2026-10-17)

- main3e1ba64의 CI38981041166 overall SUCCESS, 필수 gate 및 6개 publish 모두 SUCCESS(root 실제 API 조회). 실제 checkout3e1ba64, XML228/1292tests 실패·skip0, 양 E2E A–E 동일mainSHA 성공을 독립 검증자가 확인했다. 원격 SHA/latest manifest 최종 대조는 진행 중.
- 과거 payment unknown blob의 상세 원인은 여전히 미확정이다. 이번 진단 보존 변경의 main 배포 성공을 확인했으며, 원인 없는 실패 run 재실행을 하지 않았다.
- 기존 완료 PR175/176 계획6건의 아카이브와 참조 정리를 root가 시작했다. 현재 범위의 부채 완료는 아직0이며 #177 최종 E2E/독립 검증 대기.

- 복구 main 독립 최종 PASS: CI23jobs 모두 SUCCESS, 6서비스 공개GHCR SHA태그/latest manifest digest 및 config 동일. 일반 부채 병합 차단 해제. 원격 조회 시점 main3e1ba64 기준이며 이후 latest 이동과 구분한다.

| 서비스 | main3e1ba64 SHA/latest 공통 digest |
|---|---|
| gateway | `sha256:0e9339a679aa6949cb1be378aa4eaefa9dd073de910c16e4668f9dee6ec0db00` |
| user-service | `sha256:6b3e427300502cd080309e7607ccb6c674d663a2935aea365f1c85d113ac2157` |
| product-service | `sha256:90feb3e02ae3a0a6b80dba2a987a7e497caa01664aec624bcaad0023740e7151` |
| order-service | `sha256:4e25807ebb20584a62f39d9a89fdafd3709ba1d38caa060f8163ce39b7fbeeca` |
| payment-service | `sha256:6c814e661e8d22b220f985c92489a2c8ee056c81efac390edf7d561f3b54267e` |
| notification-service | `sha256:8ee2fc6a6b292e689294ca6cc7339dc0b3a617f1c5d3b3b65f0be5e3cc18045d` |

## 첫 부채 묶음 병합 (2026-10-17)

- #177 headcb58da0/base3e1ba64 최종 독립 PASS. CI38981159191 필수17jobs/gate SUCCESS, 실제 checkout `4bde8cced2ff8b4ec43bc6a476bcda59ef4cbdbb`의 부모 일치, XML229/1296tests 실패·오류·skip0, 양E2E 동일checkout A–E/이미지 identity/음성 대조군 확인. PR138 정정과 a559 원본 permalink도 독립 readback.
- root가 PR 본문에 증거를 기록한 뒤 정확 head 조건으로 #177 정상 merge. 병합 SHA `850b0d448ebc6e46524aa2576545b3d55c1ff9d6`, 시각2026-10-16T23:40:06Z. main CI https://github.com/Kimgyuilli/PeekCart/actions/runs/38982636776 진행. 다음 병합과 D083/068/080/073/077 완료 기록은 이 main 검증 뒤 진행.
- 기존 #175/#176 완료 계획6건(11파일)을 done/로 이동, 인바운드 참조1곳 갱신, done index89행/check 및 harness-context check 통과. 상위 orchestration은 진행중으로 유지.
- 다음 앱6건과 Saga/monitor4건은 #180에 통합해 전체 테스트와 새 E2E를 함께 검증한다. 개별 계획·등급·증거는 유지. A local68246 통합 무충돌, D071 V13에 맞춰 E2E의 정확 migration집합에13 추가, step표시6종 정정. 기존180원격e417 CI는 유지하며 결과관측 후 최신base/기록/실험증거로 갱신한다.
- D063 run2 exit1: 실제 rule.state=firing/instance.state=Alerting인데 파서가 instance firing을 기대해 sustained끝 assert 실패. 실제5rule 발화 원문은 있으나 healthy 복귀 미실행, 완료 아님. session80307 종료·project1792192171 ps-a0 cleanup 확인, 슬롯반환. 실제응답fixture로 파서계약 수정·독립검토 후 run3 필요.
- heavy 슬롯을 B D084 집중검증으로 전환 승인(abbe001, --no-parallel/--max-workers=1/--no-daemon). D063 run3는 집중검증 종료·cleanup과 수정독립PASS 후 다시 배정. 두 실행 중첩 금지.

- #180 기존 e417/base3e1 CI38981330710 독립 증거 PASS(후속 통합 head의 병합 승인은 아님). 실제checkout9c0834e 부모 일치, 필수17jobs/XML1292 실패·skip0, E2E 이미지·음성 대조군 확인. D061 F에서 실제 POST/ALREADY POST→GET/reconcile GET만/transient POST3 동일key, 소비자3곳 종착 및 UNRES 생산outbox0와 합성 소비 검증 구분 확인. D062 groupEmpty, 실제DLT p0o0 committed2→0 rewind 후 같은 원장 attempt2 확인. D62초/F122초/전체job12분5초로45분 상한 이내.
- 진단 #179는 main 배포까지 확인되어 개별 P3를 완료하고 계획을 done/로 아카이브, index90행/check통과. foundation177은 별도로 새main 검증 대기이므로 아직 완료 이동하지 않았다.

- B D084 focused 실행 session86805, clean head `499d8e1f6ca8969b147f73808131bcd68fd1f3a5`, 로그 `/private/tmp/peakcart-d084-focused.log`, offline/no-parallel/no-daemon/max-workers1, seed1792194145976. common/Payment compile 통과 후 테스트 진행, 종료·cleanup까지 heavy 독점.
- A local통합0cd0fd2는 app/Saga/root22a/main850을 무충돌 포함. b41673f에서 D061/062 audit에 기존 e417 CI의 실제checkout `9c0834e2612061f3b4ea649b759c55bd2d4e71c4`, D62초/F122초 증거를 기록했다. 해당 원본head의 P1~3 검증과 최신통합CI/병합·기록 P4 대기를 분리. 원격180은 아직e417이며 새통합push전.

- D084 focused499d8e session86805 exit0/1분14초. common19/payment58/product38, 합계115tests 실패·오류·skip0. Payment seed1792194145976, Product1792194179479. 실제 MySQL 경합·historicalFAILED·4consumer/inbox·6bulkversion·서명HTTP 포함. 종료 후 소유 Testcontainers0 확인 및 슬롯반환. 전체CI/최신독립검증은 미완.
- D063 parser `460217e` 실행 전 독립PASS: 관측 fixture3개가 run2 원문112표본의 실제 응답과 일치, 회귀5개 직접PASS. rule firing과 instance Alerting 분리, 정상은 inactive+전체Normal+존재하는0vector를 요구해 NoData/빈값/잘못된state 거부. expr/for/labels 불변. root가 B run3의 새output/project 실행 승인, 시작session 대기. run2는 발화만 확인했고 복귀 미검증 상태 유지.

- D063 run3 시작 session83995, clean `460217ec3dd4b869f87d1199191d49a57b006b68`, project `peakcart-alert-1792194274`, evidence `/private/tmp/peakcart-d063-20261017-run3`와 sibling.log. 정본/provision hash는 run2와 동일. B가 종료·cleanup까지 heavy 독점.
- D084115건 XML은 독립 대조 완료. 추가 ADR 반례(localrefund CANCELED 대사 없음/FAILED/금액 불일치/정상SUCCEEDED, historicalFAILED 원인·deadline 부적합)를 새 테스트로 보강해 DraftPR CI에서 검증한다. 현재 단일 로컬 슬롯은 D063가 사용하므로 로컬 추가 Gradle은 실행하지 않는다.

- D084 Draft https://github.com/Kimgyuilli/PeekCart/pull/181, head `2ef2b7f3f23d4672418131e8f4d931f203cf484e`, base850b0d4, CI38983263940 시작. 기존115PASS/새반례6CI대기/전체D084미완/ADRProposed를 명시했다.
- root가 PR 생성 직후 E2E prerequisite 추가 갭 발견: Product V12(및 Payment 새 DDL)와 saga_e2e EXPECTED_MIGRATIONS의 기존 집합 불일치. B가 정확한 버전 집합을 대조·정정하고 경량 검증 후 새 head로 push한다. 가드 제거/동적 허용은 금지하며 이전run이 대체되는 원인을 이 소스 수정으로 기록한다. 향후 app의 Order V13 통합도 함께 보존해야 한다.

- D084 #181 최신 head `9f763181117a12a498943c09ef315ffb93e2f652`, 새 CI38983400228. 실제 migration 집합 order12/product12/payment13/notification8과 정확히 맞췄고 신규 업무4토픽 및 DLQ readiness도 추가. 독립 소스 PASS. A의 추가 읽기 조사에서 emitter 토픽/그룹·예약version 기본값·기존E/F seed·기본닫힘권한 기동에 다른 누락 없음.
- 이전head CI38983263940은 새소스검증과 혼용하지 않으며 root가 대체 실행 취소 요청을 제출했다. 실패 우회/무근거 재실행이 아니라 명확한 prerequisite 소스 수정에 따른 대체다.
- #181 단독은 기존 A–E 회귀이며 #180의 새F는 아직 포함하지 않는다. 새ADMIN 복구 체인의 증거는 focused 실제DB/서명HTTP/직렬화consumer·inbox 매핑이며 기존 Saga CI를 그 체인 자체 실행으로 확대하지 않는다. 향후 통합 시 Order13/Product12/Payment13 정확 집합을 보존한다.

- A 최종 로컬 통합 준비 head `8928ed4a6e116855d923cb17b0f0eca0ccbca5c9`: app+recovery9f+monitor460+root22. 충돌4곳(data-design/ADRindex/runner migration/lint요약)을 양계약 보존으로 해결. 독립 검토에서 실제migration13/12/13/8, 신규4topic 및 jitter0.5, ADR0036/37 Accepted와0040 Proposed, D063대상5rule 모델동일 확인. 독립 경량lint47/parser5 PASS. 원격push/최종CI는 아직 대기.
- 이전181 CI38983263940 completed/cancelled 확인. 최신38983400228은 진행중이며 실패 없음(조회 시점). root 조회 한 건은 자동 권한 검토 시간초과로 실행되지 않았고, 허용된 읽기 전용 재시도1회로 정상 조회했다. CI 자체의 실패/재실행으로 기록하지 않는다.

## Foundation 완료 기록

2026-10-17 [PR177](https://github.com/Kimgyuilli/PeekCart/pull/177) 병합 SHA `850b0d448ebc6e46524aa2576545b3d55c1ff9d6`. PR head `cb58da0944fc49ee14756f307328662f8bcca39a`, base `3e1ba64c715d22b1f5a2d2635a97da17eb4ebb28`, 실제 checkout `4bde8cced2ff8b4ec43bc6a476bcda59ef4cbdbb`의 [CI38981159191](https://github.com/Kimgyuilli/PeekCart/actions/runs/38981159191)는 필수17jobs/gate와 XML229/1296tests 실패·오류·skip0, 양E2E 이미지 identity/동일SHA A–E/음성 대조군을 통과했다. 독립 verifier가 최신 head/base와 실제 checkout 부모, artifact를 대조해 PASS를 기록했다.

병합 후 [main CI38982636776](https://github.com/Kimgyuilli/PeekCart/actions/runs/38982636776)는 실제 checkout850b0d4, 같은1296tests와 양E2E, gate를 포함한23jobs 및6publish 모두 성공했다. 공개 GHCR6서비스의 SHA태그/latest manifest digest와 config 일치도 독립 확인했다. root만 보호 규칙을 지켜 병합했으며 별도 codex exec 리뷰는 호출하지 않았다. 무료 조건은 공개 저장소·표준 runner·GHCR 확인 및 사용자의 artifact/cache 무료 범위 확인으로 충족했다.

D083/068/080/073/077을 TASKS에서 TASKS-done으로 이동하고 PHASE5 및 각 plan/audit를 갱신했다. 개별 계획 아카이브·인덱스와 기록 커밋의 후속PR 병합은 계속 진행한다. 제품 변경의 main 검증 완료와 기록 커밋의 반영 상태를 구분한다.

| 서비스 | main850b0d4 SHA/latest 공통 digest |
|---|---|
| gateway | `sha256:3523eb2d9294e132f3712dd0fecc8079fc21e0c47d296575aab1b9078471e810` |
| user-service | `sha256:58147426865038aaed2d77a369033d0d08328730db89419374f6a82faf7c7d34` |
| product-service | `sha256:2c9ada3b7040543e215a455585ce64f78248a09ca77ee64ca8810a2dc7aed81f` |
| order-service | `sha256:605485978b8101ad98788b10212fac2f04e0e900736daffe52fd8423110bd90b` |
| payment-service | `sha256:5a5a893515c3ad266a3812e074cd9a8e8d271e97d1d028416e0788a6349882f4` |
| notification-service | `sha256:1e95e75894f3e4dda338eaa3e225f1c28dcdd4a33326d588dfcefcbad7afde18` |
