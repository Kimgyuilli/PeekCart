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

## 2026-10-21 — draft PR 생성

- PR: https://github.com/Kimgyuilli/PeekCart/pull/202, branch debt/d065-constraints-plan, base actual main4a4a36a.
- 실행 도구/CI commit f3993cf, 계획·실제 증거 commit33614db. 최종 PR에서는 source-manifest의 55개 파일 동일성을 다시 확인한다.
- ship preflight/grade L/consistency/명시 staging 분류/whitespace/commit lint PASS. PR 본문 첫 문체 검사에서 평서형 어미를 지적받아 규약대로 수정 후 PASS, title PASS.
- draft PR 생성 후 전체 CI 및 독립 exact-head 검토 대기. 아직 병합 승인 없음. D-065 전체 완료 및 운영 전환 준비 완료를 주장하지 않는다.
- done 범위: 이 task plan/audit 및 evidence README만 갱신했다. TASKS/master/PHASE는 root 소유이며 별도 최신 사실로 갱신한다. Layer1 운영 계약은 바꾸지 않았다.

## 2026-10-21 — 독립 P2 cleanup 경계 수정

- 독립 검토: https://github.com/Kimgyuilli/PeekCart/pull/202#issuecomment-6312033007. f384의 SQL33/unit10/source55 자체는 통과했지만, 실제 Docker 생성 성공 후 응답 전 SIGKILL에서 owner manifest 부재로 cleanup이 자원을 남기는 P2를 확인했다. 최초 interruption 증거는 manifest 이후에만 해당했다.
- Docker 호출 전 name+128bit nonce를 atomic manifest로 보존하고 생성 nonce label을 추가했다. ID/started 응답을 받지 못해도 cleanup이 exact name/class label/nonce를 확인한 뒤 조회된 immutable ID만 삭제한다. 응답 ID를 받은 뒤에는 ID도 대조한다. foreign same-name nonce는 거부하며 실패 진단/정리 상태를 보존한다.
- 생성·삭제 소유 검사는 Python assert 대신 명시적 RuntimeError로 검사한다. CI는 optimized Python에서 ownership 경량5를 실행한다. 의도적 SIGKILL용 추가 fixture는 CI에서 만들지 않고 Docker2methods는 skip하며 실제 로컬4조건으로 별도 검증한다.
- nonce 1차 수정 actual ownership7methods/4Docker조건 PASS(7.805초) 및 SQL33 PASS. 명시적 검사 보완 뒤 최종 결과는 cleanup-fix 증거에 별도로 기록한다. 기존 f384 증거는 덮어쓰지 않는다.

- 최종 explicit ownership 검사 source: session22755 actual MySQL33 PASS/175.519초, completed=true/cleanup=true. current56 source hash가 실행 manifest와 일치한다.
- actual cleanup7methods PASS/5.284초(ownership5+Docker2methods/4조건). Python -O ownership5 PASS, Docker2methods는 그 명령에서 명시적 skip. classifier10 PASS. 새CI는 optimized ownership 검사만 포함하며 의도적 생성중 SIGKILL 대조는 로컬 증거로 구분한다.
- 자기 fixture 전부 정리, original momens3개만 확인하여 heavy 반환. 독립 P2 final-head 재검토와 최신 full CI는 아직 대기이며 최초 리뷰를 해결됐다고 임의 표시하지 않는다.
