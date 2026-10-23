# ADR-0044: CI 임계경로 단축 — 문서 전용 skip, images 의 lint 비대기, e2e 3러너

- **Status**: Accepted
- **Date**: 2026-10-24
- **Deciders**: 프로젝트 오너
- **관련 Phase**: Phase 5

## Context

ADR-0030 D1 이후 PR CI 는 약 19분, main push 는 publish 를 포함해 약 25분이다. run
[40007646179](https://github.com/Kimgyuilli/PeekCart/actions/runs/40007646179) 의 job 시각을
보면 전체 시간을 정하는 경로는 하나다.

```
lint 4.4m → images 1.5~2m → e2e(scenarios) 12.1m → gate
```

test 샤드(최대 5.0분)·guards(0.8분)·음성 대조군(6.8분)은 이 경로와 병렬이라 전체 시간에
기여하지 않는다. lint 안의 self-test 를 줄여도 lint 가 이 경로에 있는 동안만 효과가 있다.

- `images` 가 `lint` 를 기다리는 것은 D-031 이 `gate` 대기를 완화하며 남긴 의존이다. 이미지
  빌드가 lint 결과를 입력으로 쓰지는 않는다.
- scenarios 러너는 시나리오 6종을 한 스택에서 차례로 돈다. 스택 기동이 약 2.5분이고,
  시나리오 e(승인 fence, payment 재시작) 하나가 4.5분이다.
- 문서만 바꾼 PR(#223, #224)도 19분을 전부 돌았다. 브랜치 보호의 필수 체크는 `gate` 하나다.

## Decision

1. **`changes` job 이 PR 의 변경 경로를 판정한다.** PR merge 커밋의 `HEAD^1..HEAD` 를
   `--no-renames` 로 비교한다. 모든 경로가 `docs/**`(단 `docs/plans/fixtures/**` 제외) 또는
   루트 `*.md` 이면 `code=false` 다. 목록이 비거나 push 이벤트이면 `code=true` 다. 판정은
   `scripts/ci-docs-only.sh` 한 곳에 둔다.
2. **`test`·`guards`·`images` 는 `changes` 만 기다리고 `code == 'true'` 일 때만 돈다.** `e2e` 는
   `images` 만 기다리므로 skip 이 전이된다. `lint` 는 문서 보존·harness 검사를 포함하므로
   판정과 무관하게 항상 돈다. 이로써 `images` 는 더 이상 `lint` 를 기다리지 않는다.
3. **`gate` 는 `changes` 까지 6개 job 을 받는다.** `changes`·`lint` 는 항상 success 여야 한다.
   `code == 'false'` 이면 나머지 4개는 **정확히 skipped** 여야 하고, 그 밖(출력 누락 포함)에는
   전부 success 여야 한다. 출력 누락을 문서 전용으로 읽지 않도록 `'false'` 와만 비교한다.
4. **e2e 는 `scenarios`(a·b·c·d·f), `fence`(e), `negative-control` 3개 러너로 나눈다.** 각
   러너는 자기 cold start 스택을 소유한다. E2E 증적 대조(`saga-contract-matrix-lint.sh
   --e2e-evidence`)는 러너별로 하지 않고 `gate` 에서 두 시나리오 러너 증적의 합집합에 한 번
   한다. 두 디렉터리에 같은 시나리오가 있으면 디렉터리 안 중복과 같이 실패다.

ADR-0030 의 필수 검증 집합, `gate` 단일 필수 체크, publish 의 이미지 동일성·digest 승격
계약(D2~D4)은 그대로다.

## Alternatives Considered

### Alternative A: workflow 수준 `paths-ignore`
- **장점**: 문서 전용 PR 에서 CI 자체가 시작되지 않는다.
- **단점**: lint 의 문서 보존·harness 검사도 같이 빠진다. 필수 체크 `gate` 가 생성되지 않아
  PR 이 pending 으로 멈춘다.
- **기각 사유**: 문서 PR 에서 가장 필요한 검사를 잃고, 필수 체크 계약이 깨진다.

### Alternative B: lint self-test 에 경로 필터를 건다
- **장점**: lint job 이 짧아진다.
- **단점**: lint 가 임계경로에서 빠지면 단축 효과가 0 이다. 각 self-test 는 lint 자체가
  vacuous-green 으로 썩는 것을 막으려고 들어갔다.
- **기각 사유**: 시간 이득 없이 보호만 줄어든다.

### Alternative C: 시나리오마다 러너 하나(6개)
- **장점**: 이론상 가장 짧다.
- **단점**: 러너마다 약 2.5분의 스택 기동을 반복하고, 가장 긴 e 가 여전히 하한을 정한다.
- **기각 사유**: e 를 떼어낸 2분할이 이미 그 하한(약 7분)에 닿는다.

## Consequences

### 긍정적 영향
- 코드 PR 의 임계경로가 `changes → images → e2e(최장 러너) → gate` 가 된다. 추정치는 약
  19분에서 10~11분이며, 병합 후 실측으로 확인한다.
- 문서 전용 PR 은 lint 와 gate 만으로 끝난다.

### 부정적 영향 / 트레이드오프
- lint 가 실패해도 images·e2e 가 끝까지 돌아 러너 시간을 쓴다. 저장소가 public 이라 비용은
  들지 않고, 병합은 gate 가 막는다.
- e2e 가 cold start 스택을 하나 더 띄운다.
- 문서 판정은 허용 목록이다. `docs/` 아래 파일을 test·e2e·gate 가 입력으로 읽게 되면 그
  경로를 `ci-docs-only.sh` 의 제외 목록에 넣어야 한다. 작성 시점에 그런 입력은 saga 계약
  매트릭스 하나다.

### 후속 결정에 미치는 영향
- 시나리오 배분을 바꾸면 `ci-e2e-parallel-lint.sh` 가 러너별 `--scenarios` 목록의 합이
  `saga_e2e.SCENARIO_ORDER` 와 정확히 같은지 검사한다.

## References

- `.github/workflows/ci.yml` — `changes`, `images`, `e2e`, `gate`
- `scripts/ci-docs-only.sh` — 문서 전용 판정과 self-test
- `scripts/ci-release-gate-lint.sh` — gate 전파 조건을 `changes` 출력까지 전수 대조(CRG-002·003·007)
- `scripts/ci-e2e-parallel-lint.sh` — e2e 3러너와 시나리오 배분
- `scripts/saga-contract-matrix-lint.sh` — `--e2e-evidence` 의 여러 디렉터리 합집합 대조
- ADR-0030 — CI 최종 게이트와 검증 이미지 승격 계약(D1 부분 대체)
