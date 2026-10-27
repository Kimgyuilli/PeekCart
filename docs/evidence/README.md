# 증적 색인

계획·검증 원자료와 그 README 를 계획 ID 별 폴더로 둔다. 역할과 이름 규칙은 [증적 규약](../conventions/evidence.md)에 있다. 산문 증적은 `docs/progress/evidence/`, 측정 세션 원자료는 `loadtest/reports/<날짜>/` 에 둔다.

2026-10-26 D-116 P3a 에서 `docs/plans/evidence/` 의 26개 폴더를 이곳으로 옮겼다(`D098` 은 `D-098`). 옛 경로 대응표는 [docs/plans/evidence/README.md](../plans/evidence/README.md) 에 있다.

"Actions 원본 만료" 는 2026-10-26 에 GitHub API 로 읽은 원 run 의 artifact 만료일이다. 로그 보존은 저장소 기본(90일)이라 API 로 읽히지 않고, run 생성일 뒤 약 90일로 본다. 그 뒤로는 이 폴더의 사본이 유일본이다. 로컬 실행·사람 검토·재계산 근거는 처음부터 다시 만들 수 없다.

| 폴더 | 계획 | 내용 | 원자료 | 파일 | Actions 원본 만료 |
|---|---|---|---|---:|---|
| [D-063](D-063/README.md) | [D-063](../plans/done/D-063.md) | 경보 실제 35분 실행 run1~3(실행·컨테이너 로그, 관측 jsonl, manifest·summary), 평가한 경보 규칙, 환경 동등성 메모 | 로컬 Docker 실행 | 30 · 428KB | — |
| [D-065-access-denied-mvc](D-065-access-denied-mvc/README.md) | [D-065-access-denied-mvc](../plans/done/D-065-access-denied-mvc.md) | 수정 전후 `AccessDeniedMvcContractTest` JUnit XML, platform 로그, manifest | Actions 사본 | 5 · 13KB | 2026-10-28 · 2027-01-19 |
| [D-065-collector-adapter](D-065-collector-adapter/README.md) | [D-065-collector-adapter](../plans/done/D-065-collector-adapter.md) | collector adapter 워크플로 run 3개의 artifact 와 manifest, 로컬 대조 로그 | Actions 사본 + 로컬 | 11 · 178KB | 2026-10-28 |
| [D-065-constraints](D-065-constraints/README.md) | [D-065-constraints](../plans/done/D-065-constraints.md) | 제약 조건 로컬 실험 json·로그, `cleanup-fix/` | 로컬 실행 | 21 · 41KB | — |
| [D-065-dlq-diagnostic](D-065-dlq-diagnostic/README.md) | [D-065-dlq-diagnostic](../plans/done/D-065-dlq-diagnostic.md) | DLQ 진단 JUnit XML(로컬 1, CI 4), 결과 txt, SHA256 목록 | 로컬 + Actions 사본 | 13 · 40KB | 2026-10-28 · 2027-01-19 |
| [D-065-flyway-contract](D-065-flyway-contract/README.md) | [D-065-flyway-contract](../plans/done/D-065-flyway-contract.md) | Flyway contract 워크플로 artifact 와 manifest, `first-attempt/` | Actions 사본 | 9 · 117KB | 2026-10-28 |
| [D-065-lineage-writer](D-065-lineage-writer/README.md) | [D-065-lineage-writer](../plans/done/D-065-lineage-writer.md) | 로컬 JUnit XML, SQL 원형 도구 | 로컬 실행 + 도구 | 7 · 20KB | — |
| [D-065-signed-gateway](D-065-signed-gateway/README.md) | [D-065-signed-gateway](../plans/done/D-065-signed-gateway.md) | signed gateway 워크플로 artifact 2개와 manifest | Actions 사본 | 5 · 204KB | 2026-10-28 · 2027-01-19 |
| [D-075](D-075/README.md) | [D-075](../plans/done/D-075.md) | outbox 처리율 원결과(jsonl·json), 추출 도구 2개, 로컬 gradle 로그, 요약 | 로컬 측정 + 도구 | 13 · 68KB | — |
| [D-076](D-076/README.md) | [D-076](../plans/done/D-076.md) | CI 정책 lint 명령과 로컬 실행 출력 | 로컬 실행 | 2 · 25KB | — |
| [D-078](D-078/README.md) | [D-078](../plans/done/D-078.md) | 수집기 이전 코드 대조·변이 검사 도구와 결과 | 로컬 실행 + 도구 | 6 · 14KB | — |
| [D-078-product-actions](D-078-product-actions/README.md) | [D-078-product-actions](../plans/done/D-078-product-actions.md) | product barrier 재현 artifact zip 2개(gradle 로그·JUnit XML), job 로그, result·manifest | Actions 사본 | 12 · 449KB | 2026-10-28 · 2026-11-04 · 2027-01-19 |
| [D-078-product-barrier-review](D-078-product-barrier-review/README.md) | [D-078-product-actions](../plans/done/D-078-product-actions.md) | 독립 읽기 검토 원문 | 사람 검토 | 1 · 4KB | — |
| [D-078-retry-load](D-078-retry-load/README.md) | [D-078-retry-load](../plans/done/D-078-retry-load.md) | 재시도 부하 job 로그, JUnit XML, json | Actions 사본 | 10 · 68KB | 2026-10-28 · 2026-11-04 · 2027-01-19 |
| [D-078-scheduler-capacity](D-078-scheduler-capacity/README.md) | [D-078-scheduler-capacity](../plans/done/D-078-scheduler-capacity.md) | 스케줄러 용량 API json, job 로그, JUnit XML | Actions 사본 | 13 · 37KB | 2026-10-28 · 2026-11-04 · 2027-01-19 |
| [D-080](D-080/README.md) | [D-080](../plans/done/D-080.md) | 통합 테스트 클래스별 시간 tsv, 종료 stack | 로컬 측정 | 3 · 33KB | — |
| [D-084](D-084/README.md) | [D-084](../plans/D-084.md)(보류) | focused 테스트 실행 결과 json | 로컬 실행 | 1 · 32KB | — |
| [D-084-signed-recovery](D-084-signed-recovery/README.md) | [D-084-signed-recovery](../plans/done/D-084-signed-recovery.md) | signed 복구 워크플로 artifact 와 manifest, 로컬 실패·음성 로그 | Actions 사본 + 로컬 | 5 · 74KB | 2026-10-28 · 2027-01-19 |
| [D-087](D-087/README.md) | [D-087](../plans/done/D-087.md) | CI build profile run 의 6서비스 job 로그·lint 로그·manifest·summary·`build-profile-*`, 첫·둘째 시도 | Actions 사본 | 142 · 1.1MB | 2026-10-28 · 2026-11-04 · 2026-12-27 · 2027-01-19 |
| [D-087-profile-cache](D-087-profile-cache/README.md) | [D-087-profile-cache](../plans/done/D-087-profile-cache.md) | user profile artifact zip 4개(Gradle profile HTML·provenance), job 로그, API json | Actions 사본 | 16 · 475KB | 2026-11-04 · 2027-01-19 |
| [D-088](D-088/README.md) | [D-088](../plans/done/D-088.md) | lint 재계산 inventory 와 결과 | 재계산 근거 | 5 · 16KB | — |
| [D-089](D-089/README.md) | [D-089](../plans/done/D-089.md) | Actions API 응답·publish 로그(`raw/`), 요약 json | Actions API 사본 | 20 · 58KB | 2026-10-28 · 2027-01-14 · 2027-01-19 |
| [D-093](D-093/README.md) | [D-093](../plans/done/D-093.md) | baseline·timeout 실행의 컨테이너 로그, 관측 jsonl, 규칙, summary | 로컬 Docker 실행 | 16 · 244KB | — |
| [D-094](D-094/README.md) | [D-094](../plans/done/D-094.md) | 로컬 JUnit XML 4개, patch 2개, json | 로컬 실행 | 9 · 19KB | — |
| [D-098](D-098/README.md) | [D-098](../plans/done/D-098.md) | 완료 이력 보존 검사의 로컬 실행 기록 | 로컬 실행 | 8 · 12KB | — |
| [D-099](D-099/README.md) | [D-099](../plans/done/D-099.md) | archive 건강 검사 셀프테스트·변이 결과, json. 도구는 `scripts/harness-context-mutants.py` | 로컬 실행 | 12 · 17KB | — |
| [D-100](D-100/README.md) | [D-100](../plans/done/D-100.md) | 과거 의존성 로그 발췌 | 근거 | 2 · 8KB | — |
| [D-102](D-102/README.md) | [D-102](../plans/done/D-102.md) | 테스트 주석 inventory, 통합 대조 결과. 도구와 baseline 은 `scripts/test-comments/` | 근거 | 3 · 83KB | — |
| [D-103](D-103/README.md) | [D-103](../plans/done/D-103.md) | 리뷰 렌더러 계약 로컬 결과 json·txt | 로컬 실행 | 7 · 11KB | — |
| [D-104](D-104/README.md) | [D-104](../plans/done/D-104.md) | 리뷰 수치 재계산 근거(source-scan json)와 도구 2개 | 재계산 근거 + 도구 | 7 · 59KB | — |
| [D-116](D-116/README.md) | [D-116](../plans/done/D-116.md) | Phase 5 마감 정리 전수조사 보고서 5개, 결정 기록, 정리 전 인벤토리 | 조사·결정 | 8 · 254KB | — |
| [campaign-records-review](campaign-records-review/README.md) | [task-debt-20261021](../plans/done/task-debt-20261021.md) | 캠페인 기록 독립 검토 메모와 API 응답 | 사람 검토 + Actions API 사본 | 2 · 44KB | 2026-10-27 · 2027-01-18 · 2027-01-19 |
