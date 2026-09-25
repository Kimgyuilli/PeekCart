# ADR-0030: CI 최종 게이트와 검증 이미지 승격 계약

- **Status**: Accepted
- **Date**: 2026-09-25
- **Deciders**: PeakCart
- **관련 Phase**: Phase 5

## Context

현재 `.github/workflows/ci.yml` 의 `gate` 는 `test`·`guards` 만 기다린다. `images` 는
`lint` 뒤에 빌드와 health smoke 를 수행하고, `e2e` 는 그 이미지로 시나리오와 음성
대조군을 별도 매트릭스 러너에서 검증한다. `publish` 는 `[images, gate]` 만 기다리므로
e2e 가 실패하거나 끝나기 전에도 main push 의 GHCR `:latest` 를 갱신할 수 있다.
브랜치 보호의 필수 체크 이름은 D-037 에서 이미 `gate` 로 복구됐지만 이 체크의 초록색은
lint·이미지·e2e 의 성공을 뜻하지 않는다.

이미지는 `docker save` artifact 로 e2e 와 publish 에 전달된다. 지금은 두 소비자가 로드한
이미지가 빌드 러너의 이미지와 같은지 검증하지 않고, publish 는 SHA 태그와 `latest` 를
각각 push 한다. 첫 push 의 digest 만 출력해 둘째 태그가 같은 manifest 를 가리키는지
확인하지 않는다.

## Decision

1. PR 병합과 main push 이미지 승격의 필수 검증 집합은 `lint`, `test`, `guards`,
   `images`(6개 health smoke 포함), `e2e`(시나리오·음성 대조군 2개)다. 기존 `gate` 가 이
   5개 job 을 직접 `needs` 로 받고, 실패·skip 을 모두 실패로 전파한다. `gate` 의
   `if: !cancelled()` 와 증적 수집은 유지한다. `images` 는 `lint` 만 기다리고 `e2e` 는
   `images` 만 기다려 빌드와 JVM 테스트의 병렬성은 유지한다.
2. `gate` 는 브랜치 보호의 유일한 필수 체크 이름으로 유지한다. 새 이름으로 설정을
   갈아 끼우는 창을 만들지 않는다. 관리자도 이 체크를 우회하지 않도록
   `enforce_admins=true` 로 한다. 설정 변경은 코드가 아닌 저장소 설정이므로 적용 후
   GitHub API 를 재조회한다.
3. `publish` 는 main push 에서 성공한 `gate` 뒤에만 시작한다. 이미지 재빌드는 금지한다.
   빌드 러너는 각 `docker save` artifact 에 **이미지 ID(OCI config digest)** 를 기록한다.
   e2e 와 publish 는 로드한 이미지 ID 가 기록값과 같은지 확인한다. Artifact 가 포함한
   압축 파일의 SHA-256 도 소비 전에 확인한다. PR 에서는 기존처럼 saga 4개 이미지로
   e2e 를 돌리고, main push 에서는 6개 모두 publish 에 전달한다.
4. publish 는 동일한 로드 이미지로 GHCR `:<commit SHA>` 를 먼저 push 한다. 원격
   manifest 의 `config.digest` 가 검증된 이미지 ID 와 같은지 확인한 뒤, 그 **원격
   manifest digest** 를 소스로 `:latest` 태그를 만든다. 단일 플랫폼 manifest 를 index 로
   감싸지 않고, 승격 결과 digest 가 SHA 태그의 digest 와 같아야 성공이다. 따라서
   검증 대상의 config digest 와 게시 manifest, SHA 태그와 `latest` 의 manifest digest
   관계가 모두 증명된다.

## Alternatives Considered

### Alternative A: `publish` 만 `e2e` 를 직접 기다린다

- 장점: 한 줄 수정이다.
- 기각 사유: PR 필수 체크 `gate` 는 여전히 e2e·lint·이미지 실패를 집계하지 않는다.
  브랜치 보호와 게시 판정이 서로 다른 계약이 된다.

### Alternative B: 새 최종 job 이름을 만들어 브랜치 보호를 교체한다

- 장점: 기존 JVM 증적 `gate` 의 시작 시점을 보존한다.
- 기각 사유: required check 이름을 바꾸는 동안 새 체크가 보고되기 전까지 PR 이 막히거나,
  이전 체크만 필수인 창이 열린다. 현재 `gate` 는 이미 증적 집계자라 최종 집계로 확장할 수 있다.

### Alternative C: 이미지 재빌드 또는 `latest` 를 두 번째 `docker push` 로 발행한다

- 장점: 기존 publish 절차와 가깝다.
- 기각 사유: 재빌드는 e2e 검증 이미지와 달라질 수 있고, 두 번째 push 는 결과 digest
  동일성을 확인하지 않으면 별도 이미지 승격이다. 원격 digest 를 소스로 태그를 만든다.

## Consequences

- `gate` 가 e2e 뒤에 실행되므로 JVM 증적 집계 결과를 보는 시점은 늦어진다. 전체 CI
  임계경로에는 gate 처리 시간만 추가되고, images/test 병렬성은 유지된다.
- e2e 실패 시 PR 의 `gate` 는 red 가 되고 main push 의 `publish` 는 시작하지 않는다.
  `!cancelled()` 로 실행한 gate 는 선행 실패를 마지막 단계에서 명시적으로 전파해야 한다.
- 이미지 ID 는 config digest 이고 레지스트리 digest 는 manifest digest 다. 둘을 같은
  문자열로 비교하지 않고 manifest 의 `config.digest` 연결을 검증한다.
- `enforce_admins=true` 는 관리자의 긴급 우회도 막는다. 예외 배포가 필요하면 보호 설정
  변경을 별도 운영 행위로 기록해야 한다.
- GHCR 은 6개 저장소의 `latest` 변경을 하나의 트랜잭션으로 묶지 않는다. 최종 gate
  이후 publish 매트릭스의 일부가 실패하면 성공한 서비스만 새 digest 를 가리킬 수 있다.
  이 ADR 은 검증 전 게시와 개별 이미지의 digest 드리프트를 닫으며, 다중 저장소 원자
  배포는 약속하지 않는다. GHCR→Artifact Registry 수동 승격은 기존
  `scripts/promote-images.sh` 계약을 따른다.

## References

- `.github/workflows/ci.yml` — `lint`, `test`, `guards`, `images`, `e2e`, `gate`, `publish`
- `scripts/ci-release-gate-lint.sh` — 최종 게이트 배선·실패 전파 검사
- ADR-0028 — 이미지와 테스트 병렬화, ADR-0011 — 증적 게이트
- [GitHub Actions needs/skip semantics](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idneeds)
- [Docker imagetools create](https://docs.docker.com/reference/cli/docker/buildx/imagetools/create/)
