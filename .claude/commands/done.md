현재 완료된 작업을 TASKS.md / progress / ADR 에 반영합니다.

편집 전에 비교 base 커밋을 고정한다(보통 PR base SHA, 연속 로컬 기록은 최종 PR의 base).
완료 행·계획 이동 계약과 manifest 형식은 `docs/conventions/docs-preservation.md`를 따른다.

다음을 수행하세요:

1. `docs/TASKS.md`를 읽어 현재 진행 중(`🔄`)인 Task를 확인합니다.
2. 해당 Task의 각 항목을 실제 코드베이스와 대조합니다 (파일 존재 여부, 구현 완료 여부).
3. **결정 사항 분류 (ADR 우선 원칙)**: 이번 작업 중 발생한 결정/트레이드오프가 있다면 다음 기준으로 분류합니다.
   - **ADR 후보**: "왜 X 대신 Y를 선택했는가" 의 답이 한 줄로 안 끝나거나, 대안 비교가 존재하거나, 후속 작업의 전제가 되는 결정 → **먼저 ADR 작성** (`docs/adr/template.md` 복사). 작성 후 progress / Layer 1 에서는 `(see ADR-NNNN)` 로만 참조. 결정 근거를 progress 에 풀어 쓰지 않음 (SSOT 원칙)
   - **구현 디테일**: 단일 구현 선택, 라이브러리 옵션 등 한 줄로 끝나는 것 → progress "주요 결정 사항" 표에 직접 기록
   - 확신이 안 서면 사용자에게 "이거 ADR 로 분리할까요?" 라고 물어봅니다. 임의로 ADR 을 만들지 않습니다
4. **활성 ADR 상태 점검**: `docs/adr/README.md` 인덱스를 확인합니다. 이번 작업이 다음 중 하나에 해당하면 ADR Status 전환이 필요합니다.
   - `Proposed` ADR 의 구현을 완료한 경우 → 해당 ADR Status 를 `Accepted` 로 전환
   - 기존 결정이 대체된 경우 → 새 ADR 작성 + 이전 ADR Status 를 `Superseded` / `Partially Superseded` / `Deprecated` 로 전환
   - ADR 본문의 사실 오류(파일명, 수치 등) 정정이 필요한 경우 → `fix(adr):` 접두사 + 본문 말미 `## Update Log` 절에 변경 일자/커밋/사유 기록 (README "본문 정정 예외" 규칙)
5. 완료된 항목은 `🔲` → `✅`로, Task 전체가 완료되면 Task 상태도 `🔄 진행 중` → `✅ 완료`로 업데이트합니다.
6. 완료된 Task 행은 PR 링크를 단 채로 `docs/progress/TASKS-done.md` 표 맨 아래로 옮기고 `docs/TASKS.md` 에서 지웁니다. `TASKS.md` 에는 열린 행만 남깁니다.
7. `docs/progress/PHASE{N}.md`를 읽어 현재 Phase의 진행 보고서를 업데이트합니다:
   - "작업 이력" 섹션에 오늘 날짜와 완료 항목 추가 (관련 ADR 이 있으면 번호 명시)
   - 결정 사항은 3번 분류에 따라 ADR 참조 또는 직접 기록
   - "Phase N 완료 시 체크리스트" 중 이번 작업으로 달성된 항목이 있다면 `[ ]` → `[x]`로 업데이트
8. **Layer 1 문서 정합성 점검**: 이번 작업이 환경/아키텍처/요구사항에 영향을 줬다면 해당 Layer 1 문서(01~07)도 갱신해야 합니다. 단 결정 근거(Why)는 ADR 에, Layer 1 은 현재 상태(What)만 기술하고 ADR 참조로 연결합니다.
9. 완료 행의 마지막 상태 cell만 변경하고 원래 본문은 보존한다. 여러 행은 비교 base의 TASKS 원래 순서대로 done 기존 행 뒤에 추가한다. 기존 footer/정정 이력도 보존한다. 경로가 달라진 상대 Markdown 링크만 같은 대상·앵커로 고친다. 역사 사실 정정은 좁은 hash-bound manifest가 필요하다.
10. 명시 경로와 새 manifest를 stage한 뒤 `python3 -B scripts/docs-preservation-ci.py --base <비교-base>`를 실행한다. 실패하면 보존 위반을 수정하며 예외로 파일 전체를 제외하지 않는다. 일반 현행 문서의 내용 수정은 이 이동 계약과 구분한다.
11. 변경 내용을 요약 보고합니다 (생성/전환된 ADR 이 있으면 별도로 명시).
