---
grade: L
---

# D-043 — CI 최종 게이트와 이미지 승격 경계

## 1. 명제

`lint`·`test`·`guards`·`images`·e2e 시나리오·음성 대조군 중 하나라도 실패하거나
건너뛰어도 필수 `gate` 가 성공하거나 GHCR `:latest` 가 갱신된다면 미완이다. e2e 가
검증한 이미지와 publish 가 올린 이미지의 config digest 가 다르거나 SHA 태그와
`latest` 의 원격 manifest digest 가 다르다면 역시 미완이다.

## 2. 배경 — 코드 검증

| 전제 | 확인한 현재 코드 | 처분 |
|---|---|---|
| `gate` 는 test/guards 만 집계 | `ci.yml` 의 `gate.needs: [test, guards]`, 마지막 실패 전파도 두 결과만 검사 | 5개 job 직접 집계로 확장 |
| e2e 는 2개 매트릭스 모드 | `e2e.matrix.mode: [scenarios, negative-control]`, `needs: images` | 두 모드를 모두 필수로 유지 |
| `publish` 는 e2e 전 시작 가능 | `publish.needs: [images, gate]` | `gate` 성공 뒤에만 시작 |
| 6개 빌드 중 PR e2e 에는 saga 4개 이미지 artifact | `Save/Upload image` 의 이벤트 조건과 `e2e` 의 4개 로드 루프 | 기존 범위 유지, 이미지 ID 검증 추가 |
| SHA 와 latest 는 각각 push | `publish` 의 `docker push` 두 번, 첫 digest 만 추출 | 원격 digest 기반 태그 승격·동일성 검증 |
| 필수 체크는 `gate` | D-037 PR #141 의 설정 기록. live API 재조회는 구현 후 필요 | 이름 유지, 관리자 적용은 별도 설정 변경 |

등급 L: CI 배선·아티팩트·GHCR 게시·브랜치 보호 설정이 공유 계약이고 되돌림이
워크플로와 저장소 설정 양쪽에 걸친다. 결정은 ADR-0030 에 기록한다.

`PLAN-BLINDSPOTS` 적용: B1/B1b 의 코드 이동은 없으나 `gate`·`image-*`·`latest` 문자열
소비자(`ci-release-gate-lint`, `ci-e2e-parallel-lint`, `image-contract-lint`,
`promote-images.sh`, D-037 설정)를 검색했다. B2: ADR-0030 목표는 현재 코드에 아직 없다.
B4: 아래 파일과 needs/검증 명령을 고정한다. B5: 공유 이미지 아티팩트는 기존
`image-<service>` 한 곳을 유지한다. B11: job 이름 `gate` 와 태그 `latest` 의 참조를
YAML/lint/문서에서 함께 검사한다. 나머지 B항목은 서비스·DB·런타임 경계를 바꾸지 않아 해당 없다.

## 3. 작업 항목

- [x] P1. ADR-0030 을 확정하고 `docs/adr/README.md` 에 등록한다.
- [x] P2. `.github/workflows/ci.yml` 의 `gate.needs` 와 마지막 실패 전파를
  lint/test/guards/images/e2e 로 확장한다. `publish` 는 gate 성공 뒤에만 시작하며
  `images → e2e` 와 images/test 병렬성을 유지한다.
- [x] P3. 빌드 이미지 artifact 에 이미지 ID 와 tar SHA-256 을 넣고, e2e·publish 가
  로드 전에 checksum, 로드 후 이미지 ID 를 확인한다.
- [x] P4. publish 가 동일 이미지를 SHA 태그로 push 하고 원격 manifest 의 config digest 를
  확인한 뒤 그 manifest digest 로 `latest` 를 승격하고 결과 digest 동등성을 검사한다.
- [x] P5. `scripts/ci-release-gate-lint.sh` 를 최종 필수 검사·게시 경계·이미지 동일성
  배선까지 검사하도록 갱신하고 조작 입력으로 각 필수 검사의 우회를 검출한다.
- [x] P6. `docs/06-testing-strategy.md` 의 CI 게이트 현재 상태를 갱신한다. 브랜치
  보호의 `gate`/GitHub Actions 앱/strict 를 재확인하고 `enforce_admins=true` 로
  적용·재조회한다.

## 검증 방법

- V1. lint fixture 에서 필수 job 각각을 `needs` 와 실패 전파에서 제거하거나 skipped
  성공으로 처리하도록 바꾸면 `ci-release-gate-lint --self-test` 가 실패를 잡는다.
  e2e 한 모드 삭제와 이미지 smoke/identity 검증 삭제도 검출한다.
- V2. 실제 워크플로 YAML 에서 5개 선행 job 실패·skip 각각에 대해 `gate` 결과가
  실패로 귀결되고 `publish` 가 gate 를 우회할 수 없는지 독립 시뮬레이션한다.
- V3. 이미지 ID·checksum 불일치를 주입한 검증 스크립트는 실패한다. 정상 아티팩트는
  e2e/게시에서 같은 ID 를 보이고, 원격 manifest `config.digest` 는 그 ID 와 같다.
- V4. 원격 SHA 태그 digest 와 `latest` 승격 결과 digest 불일치 주입은 실패한다.
  GHCR 실측에서는 두 digest 가 일치해야 한다.
- V5. `./gradlew test` 와 관련 lint 전체 통과. PR CI 에서 `gate` 가 두 e2e 모드를
  기다리는지, main push 에서 성공한 gate 뒤에만 publish 가 시작하는지 확인한다.

## 완료 조건

P1~P6 과 V1~V5 를 모두 충족하고 GitHub 브랜치 보호가 관리자에게도 `gate` 를
요구하면 완료다. PR/main push 실측과 외부 설정 재조회 전에는 구현이 끝나도 검증은
미완으로 표시한다.

## 미해결

- D-041/042 의 e2e 검증 책임 재배치는 별도 작업이다. 이번 작업은 두 모드를 모두
  필수로 유지한다.
- GHCR 게시 권한은 main push 의 `publish` 에만 둔다. PR 에 후보 이미지를 push 하지 않는다.
- GHCR 6개 저장소의 `latest` 를 원자적으로 교체할 수는 없다. publish 매트릭스 일부
  실패 시 부분 승격 가능성은 ADR-0030 §Consequences 에 기록한다.
