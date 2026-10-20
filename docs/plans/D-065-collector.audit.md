# D-065 collector 감사 기록

## 2026-10-21 계획·구현 확인

- 상태: 별도 Codex 리뷰 미호출.
- 기준: origin/main `eb5dee5`, 작업 브랜치 `debt/d065-collector`.
- 범위: 실제 배포 preflight의 수집 오류 전파, Kafka CLI 형식 검증, DB 집계 행 및 DATETIME(6) 소수초 판정, PATH adapter 회귀 fixture.
- 재현: 기준 커밋의 스크립트 복사본을 실제 normal-mode로 실행하여 표적 SQL 실패, 첫 Kafka 빈 응답, 둘째 Kafka header-only 응답이 각각 exit 0임을 확인했다. 수정본은 이 응답을 성공으로 해석하지 않는다.
- 코드 전제: V11 migration은 `last_replay_settled_at DATETIME(6)`를 선언한다. UNIX_TIMESTAMP의 소수초 출력에 정수 변환을 쓰면 정상 수집이 거절되므로 Decimal로 보존한다. fixture는 소수초 응답과 상한 1마이크로초 전·정확한 상한을 포함한다.
- 검증: 판정 self-test 16개 및 collector 64개 시나리오(8 methods) 전체 통과, 추가 대기 실패 1개 시나리오 통과. 최종 runner는 9 methods/65개 시나리오다. replay-entrypoint lint와 drift self-test, shell/Python 구문, git diff --check, consistency precheck 통과.
- 검증 대기: 전체 CI. 로컬 Gradle/Docker는 병렬 작업의 자원 사용 제한으로 실행하지 않았다.
- 한계: fixture는 kubectl의 CLI 인자/응답과 수집 코드 경로를 검증한다. 실제 MySQL/Kafka adapter, GKE, replay 개방, P22 표본, writer/NOT NULL 계약은 검증하지 않았다. D-065 전체는 부분 완료로 유지한다.
- 공용 완료 문서: TASKS/progress/ADR index/master plan은 주 작업자가 관리한다.
