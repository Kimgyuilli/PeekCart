# 계획 블라인드스팟 체크리스트 (compound)

> **무엇**: 과거 계획들이 *실제로* 놓쳐서 구현 단계에서 터진 패턴의 누적 목록.
> **언제 쓰나**: `/plan` Step 4 GP-1 이 구조 변경(모듈/경계/이동)을 감지하면, 본 목록의 각 항목을 계획서에서 다뤘는지 점검하고 plan-review(Step 7) 프롬프트에 입력으로 먹인다.
> **어떻게 자라나 (필터 — 기본값은 "여기 저장 안 함")**: 구현이 계획과 크게 달랐던 항목이 나오면, prose 로 적기 *전에* 3-질문 라우팅을 적용한다.
> 1. **반복되나** — 이 한 건이 아니라 미래 작업의 *한 부류*에 적용되나? · 2. **재도출 어렵나** — 다음 세션이 레포/깃/테스트에서 싸게 다시 알아낼 수 없나? · 3. **더 싼 자동검사로 못 바꾸나**.
> - 자동화 가능(3이 No) → **가드/픽스로** ("자동화 후보" 절에 백로그). prose 저장 안 함. ← 가장 강한 compounding
> - 1·2·3 **모두 Yes** → 비로소 `Bn` 한 줄(계획 규율) 또는 메모리.
> - 그 외(반복 안 함/재도출 쉬움) → **폐기**.
> **가지치기**: 자동검사로 승격된 항목은 `Bn` 에서 제거하고 "승격됨"으로 이동. 본 목록은 *아직 자동화 못 한, 반복되는 계획 규율*만 담는다(비대화 방지).

각 항목: **Trigger**(언제 적용) · **Check**(무엇을 확인) · **출처**.

---

## B1 — 역의존 스윕 (이동/추출/peel/rename)
- **Trigger**: 패키지·모듈·공용물을 옮기거나 서비스를 분리할 때.
- **Check**: 옮기는 대상을 **밖에서** 참조하는 모든 곳을 `grep -rn "<옮기는 FQCN/패키지>"`(대상 폴더 제외)로 뽑아, **각 인바운드 간선마다 "이동/디커플/유지" 처분을 계획서 §2 에 한 줄씩** 적었는가. **테스트는 컴파일러가 강제하므로 특히** 누락 금지.
- **출처**: PR2a-2b — root `IdempotencyIntegrationTest`/`OutboxKafkaIntegrationTest`/`DlqIntegrationTest` 가 떼어낸 notification 도메인을 검증 proxy 로 쓰고 있었음(미계획 3개 테스트 재작성).

## B2 — ADR 타깃 ≠ 현재 코드
- **Trigger**: 계획이 ADR 의 목표 위치/구성요소를 인용할 때("X 는 모듈 Y 가 소유").
- **Check**: 그 타깃이 **이미 코드에 존재하는지** 1줄로 확인(파일 존재/grep). 없으면(또는 "이연" 주석이면) "만든다"를 **명시 작업 항목으로 승격**. ADR 은 목표(should), 코드는 현재(is) — 둘을 합치지 말 것.
- **출처**: PR2a-2b — N5 가 "actuator 는 observability S4 기여로 합친다"고 전제했으나 `ActuatorSecurityConfig` 가 아직 없었음(S1만 존재, S4 "PR2 이연").

## B3 — 공유 테스트 인프라 소유처
- **Trigger**: test config / fixture / 테스트 base / mock 빈을 서비스로 옮긴다고 적을 때.
- **Check**: 그게 **여러 모듈에서 쓰이는지** 확인. 둘 이상이면 단일 서비스가 아니라 **`:common` testFixtures**(공유)로 가야 한다.
- **출처**: PR2a-2b — `IntegrationTestConfig` 를 "notification test 로" 적었으나 root 테스트도 사용 → `:common` testFixtures.

## B4 — 추상 의도 금지, 구체 메커니즘 강제
- **Trigger**: 계획 항목이 "…만 배선 / …위치만 / …처리" 처럼 추상으로 끝날 때.
- **Check**: 정확한 **파일/경로/배선**을 명시했는가. 구체화하는 행위 자체가 숨은 의존을 끄집어낸다.
- **출처**: PR2a-2b — "공유 V1~V4 실행 위치만 배선" 이 정작 "어디 둬야 서비스 테스트가 읽나"라는 결정을 비워둠 → 구현 중 `db/migration`→`:common` 이동 결정.

## B5 — 공유 리소스의 물리적 위치
- **Trigger**: 여러 모듈이 공유하는 리소스(스키마/마이그레이션/정적 자원)를 다룰 때.
- **Check**: **모든 소비자**(런타임 + 모듈별 테스트 fixture)가 클래스패스/빌드 컨텍스트로 닿을 수 있는 단일 위치를 정했는가. (Dockerfile COPY 컨텍스트도 — [[project_multimodule_dockerfile_context]])
- **출처**: PR2a-2b — `db/migration` 이 root 에 있으면 분리된 notification 테스트가 못 읽음 → `:common` 단일 소유로 결정.

---

## 자동 검사로 승격된 항목 (참고 — 더 이상 수동 점검 불필요)
- 서비스↔서비스 project 의존 금지 → `assertNoServiceProjectDeps` (PR2a-2b).
- 동일 FQCN 모듈 중복 / `JwtProvider` 잔존 금지 → `assertNoDuplicateGlobalFqcn` (PR2a-2b).

## 자동화 후보 (승격 대기 — 가드/픽스로 만들 것, prose 규율 아님)
> 3-질문 필터에서 "자동화 가능"으로 라우팅된 것들. 만들어지면 위 "승격됨" 으로 이동.
- **ship: partition 커밋 후 untracked 0 검사** — 디렉토리 pathspec 으로 add 하면 모듈 루트 파일(`*-service/build.gradle`)을 놓칠 수 있음. `/ship` Step 4 말미에 "staged 외 추적 대상 잔여 0" assert. (출처 PR2a-2b, PR2b/c/d 반복)
- **ship: drift 디텍터 rename 처리** — `hpx_diff_absorption_status` 가 rename 많은 diff 에서 커밋 0건인데 `partially_live` 오판. `git status --porcelain` 의 `R old -> new` 양쪽을 매칭하도록 수정. (출처 PR2a-2b, rename-heavy peel 반복)
