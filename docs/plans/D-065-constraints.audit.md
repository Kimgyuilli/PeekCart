# D-065 constraints audit

## 2026-10-21 — 계획 작성

- 상태: plan-only. 별도 Codex 리뷰 미호출. 독립 검토 통과를 주장하지 않는다.
- 기준 main a98ae7d; 선행 PR201 b288e3a는 별도 검토/CI 중이며 본 단계의 테스트 결과가 아니다.
- 확인: 기존 INSERT IGNORE, 네 DB backfill, OutboxEvent discriminator, repository 집계·purge, raw INSERT fixture, 최소 권한 GRANT, ADR-0020/0021 및 선행 ADR-0043.
- 중단 조건: old writer 부재를 SQL로 증명 불가, 모호한 legacy 자동 보정 금지, MySQL DML rollback과 다중 DDL partial state 구별.
- 실행하지 않음: MySQL/Gradle, migration 작성·실행, 운영 데이터/환경 접근. 계획 형식 및 문서 lint는 별도 실제 결과로 기록한다.

- 실제 형식 확인: task ID validation 및 plan grade L 확인, 새 문서 whitespace 검사 통과. writing-lint는 commit/PR 전용이므로 계획 본문 검사로 주장하지 않는다.
- consistency precheck: warnings(exit 1). 현재 main 기준 tree에는 선행 PR201의 ADR-0043 파일이 없어 MISS가 발생했다. 선행 PR을 명시한 계획 참조이며 파일을 복제하거나 기존 ADR을 고치지 않았다. 실제 선행 main 통합 후 재검증한다.

## 2026-10-21 — 로컬 fixture 구현과 SQL 반례

- Root가 무료 P2–P5 구현을 승인했고 C heavy 슬롯에서만 실행했다. 최초 명제의 “설명할 수 있으면”을 “설명할 수 없으면”으로 바로잡았다. P6는 사용자 재승인 요구가 아니라 다음 PR 설계/검증 순서다.
- P2 범위를 offline fixture snapshot classifier로 명확히 축소했다. 실제 DB 조회는 자기 disposable lab뿐이며 export 진위·완전성·최신성, allocator/live writers, Flyway 이력은 검사하지 않는다. 전체 D-065 PARTIAL 유지.
- 선행 PR201 b288 통합 0560660 후, 실제 main4a4 정상 통합 eca9a5e. 실행 코드의 파일 delta는 0. 이전 ADR0043 참조 warning은 실제 선행 통합 후 consistency PASS로 해소했다.
- 최초 run1 session59600은 MySQL 초기화 임시 서버 ping 뒤 socket 종료로 ERROR2002 실패했다. 데이터 판정 전 fixture readiness 결함이며 원본 log를 보존했다. 초기화 완료 marker와 ping을 함께 확인하도록 수정했다. 초기 경량 JSON-array 음성 대조의 traceback 노출도 확인 후 입력 shape/고정 오류 출력으로 수정했다.
- run2 session81225: SQL29 PASS/96.53초. run3 session66836: 추가 동시 writer/implicit commit/allocator 충돌 등 SQL33 PASS/115.522초. 최종 진단 count 캡처와 source manifest 추가 후 run4 session26762: SQL33 PASS/108.805초, exit0. final evidence 55개 source hash가 현재 bytes와 일치함을 확인했다.
- 경량 Python 10 PASS. 실제 export/provenance로 offline CLI 호출 시 candidate10/10, rollout_ready=false 확인. 테스트가 실제 production provenance를 인증하지 않는다.
- pipeline negative session49420: CI와 같은 bash pipefail+tee에서 --fail-after-start가 expected exit1, 뒤 success marker 미도달. finally cleanup=true/completed=false. 독립 cleanup 재실행도 성공했다.
- forced-interruption session55134: manifest 작성 뒤 process SIGKILL(-9), 별도 cleanup이 실제 자기 container 제거, exit0. 원 실패 상태를 성공으로 바꾸지 않았고 기존 세 momens 컨테이너만 남음을 확인했다.
- CI는 기존 lint job에 새 named execution/always cleanup/always artifact 세 step만 추가했다. E D098은 첫 checkout 뒤 별도 위치를 소유하며 서로 기존 policy body를 수정하지 않았다. timeout8분/cleanup2분, mysql8.0.46 버전 확인과 image ID 기록.
- 실제 lint: ci release gate 및 self-test55변이, matrix coverage, mysql image pin, migration grant53, consistency PASS. 최신 전체 CI는 별도 필수이며 로컬로 대체하지 않는다.
- 별도 Codex 리뷰 미호출. 기존 독립 reviewer의 사전 소스 검토는 실행/PASS 평가가 아니며 final PR exact head 리뷰는 별도로 요청한다.
