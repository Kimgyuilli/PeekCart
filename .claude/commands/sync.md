현재 프로젝트 상태를 파악합니다.

## 0. 완료된 계획서 정리 (먼저 실행)

```bash
scripts/plans-archive.sh
```

머지가 끝난 작업의 계획서가 `docs/plans/` 루트에 남아 있는지 훑는다. `/ship` 은 PR 을
만드는 시점이라 아직 머지 전이므로 그때는 판정할 수 없다. 머지는 대개 세션 사이에
일어나기 때문에, **다음 작업을 시작하는 이 자리가 판정 시점**이다.

- `완료 0` 이면 조용히 넘어간다. 보고하지 않는다
- 이동할 것이 있으면 목록을 제시하고 `scripts/plans-archive.sh --apply` 여부를 확인받는다.
  스크립트가 계획서와 audit 을 `done/` 으로 옮기고 **인바운드 참조도 함께 고친다**
- `보류` 는 계획서 안에 PR 링크가 없어 자동 판정이 안 되는 경우다. 증거 없이 옮기지
  않는 것이 설계 의도이므로, 목록만 보고하고 사람의 판단을 기다린다. 실제로 끝난
  작업이면 **PR 을 추적해 계획서에 링크를 적은 뒤** 다시 돌리면 잡힌다. 추적은
  `git log --follow -- <계획서>` 로 마지막 커밋을 찾고
  `gh api repos/<owner>/<repo>/commits/<sha>/pulls` 로 PR 을 얻는다. 번호를 짐작하지 않는다

이동이 있었으면 인덱스를 다시 만든다.

```bash
scripts/plans-index.sh
```

`docs/plans/done/INDEX.md` 는 생성물이다. 손으로 고치지 않는다. `--check` 는 낡았는지만
확인하고 다르면 1을 반환한다.

다음을 순서대로 읽고 이해하세요:

1. `docs/TASKS.md` — 현재 Phase, 각 Task 상태, 진행 중인 항목
2. 현재 Phase에 해당하는 `docs/progress/PHASE{N}.md` — 최근 작업 이력, 주요 결정 사항
3. `docs/adr/README.md` 인덱스 — 활성 ADR 상태 확인. 특히 `Proposed` (구현 대기) / `Partially Superseded` (범위 분기) / `Deprecated` (참조 금지) 항목은 다음 작업 의사결정에 영향을 줄 수 있음
4. 진행 중(`🔄`) Task가 있다면, 해당 Task 목표에 관련된 설계 문서를 선택적으로 읽습니다:
   - 기능 범위가 불명확하면: `docs/03-requirements.md`
   - 아키텍처/패키지 구조가 필요하면: `docs/02-architecture.md`
   - DB 스키마/인덱스가 필요하면: `docs/05-data-design.md`
   - 설계 결정 근거가 필요하면: `docs/04-design-deep-dive.md` 또는 관련 ADR (`docs/adr/NNNN-*.md`)
5. 진행 중 Task에 해당하는 실제 소스 파일 존재 여부 확인
6. 최근 git log (`git log --oneline -10`)

파악한 내용을 아래 형식으로 요약 보고하세요:

**현재 Phase**: ...
**진행 중인 Task**: ...
**완료된 항목**: (Task 내 체크된 항목 수 / 전체)
**최근 주요 결정**: (progress 문서에서 파악한 최근 결정 사항 1~2줄, 관련 ADR 번호 함께 명시)
**활성 ADR 상태**: (Proposed / Partially Superseded / Deprecated 가 있다면 한 줄로. 모두 Accepted 면 "특이사항 없음")
**구현된 파일**: (실제 존재하는 소스 파일 목록, 없으면 "없음")
**다음 작업**: (미완료 항목 중 첫 번째)

정리할 계획서가 있었다면 위 보고 앞에 한 줄로 덧붙입니다. 없으면 적지 않습니다.

**계획서 정리**: 이동 N개 · 보류 M개 (보류 사유는 PR 링크 부재)
