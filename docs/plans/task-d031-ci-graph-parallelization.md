---
grade: M
codex: off
---
# D-031 — CI 워크플로 그래프 직렬화 해소

## 명제

다음 중 하나라도 참이면 미완이다.

- `images` 가 여전히 `gate` 를 기다려 `test` 샤드 완료 전에 시작하지 못한다
- `publish` 가 `gate` 를 거치지 않고 도달 가능하다 (테스트 실패 상태에서 GHCR 푸시가 가능하다)
- 이미지 빌드가 레이어 캐시 없이 매 실행 밑바닥부터 돈다
- 위 세 성질이 사람의 눈으로만 지켜지고, 어긋났을 때 CI 가 통과한다

## 배경 — 전제의 코드 검증

`/plan` §2. 인용한 전제를 ADR 이 아니라 현재 `.github/workflows/ci.yml` 과 스크립트로 확인했다.

| # | 전제 | 확인 위치 | 결과 |
|---|---|---|---|
| V1 | `images` 가 `gate` 를 기다린다 | `ci.yml:350` | **확인.** `needs: [lint, gate]` |
| V2 | `gate` 가 `test`·`guards` 를 기다린다 | `ci.yml:267` | **확인.** `needs: [test, guards]` |
| V3 | `gate` 가 선행 실패를 전파한다 | `ci.yml:344` | **확인.** `Propagate upstream failure` 가 `needs.test.result != 'success'` 에서 exit 1 |
| V4 | `e2e` 가 `images` 만 기다린다 | `ci.yml:397` | **확인.** `needs: images` |
| V5 | **`publish` 가 `images` 만 기다린다** | `ci.yml:473-474` | **확인.** `needs: images` + `if: github.event_name == 'push'`. 현재는 `images` 가 `gate` 를 물어 **간접적으로만** 게이트된다 |
| V6 | health smoke 가 로컬 daemon 이미지를 요구한다 | `scripts/docker-health-smoke.sh:100,112` | **확인.** `docker run -d ... "$IMAGE"`. 레지스트리를 보지 않는다 |
| V7 | `docker save` 가 로컬 이미지를 요구한다 | `ci.yml:381-385` | **확인.** `docker save <svc>:ci \| gzip` |
| V8 | `image-contract-lint` 가 ci.yml 을 파싱한다 | `scripts/image-contract-lint.sh:38,50-77` | **확인.** `images`·`publish` 두 잡의 `matrix.service` 를 **연속된 `- item` 행**으로만 추출한다. 매트릭스 블록 사이에 주석/빈 줄을 넣으면 조용히 잘린다 |
| V9 | 워크플로 의존 그래프를 검사하는 lint 가 있다 | `scripts/` 전체 | **없음.** `ci-test-matrix-lint.sh` 는 샤드 커버리지만 본다 |

### V5 가 범위를 늘렸다

초안(ADR-0028 §Decision 순서 1단계)은 `images: needs: [lint]` 한 줄 변경으로 적었다.
V5 확인 결과 **그대로 하면 릴리스 게이트가 뚫린다.**

```
현재    publish -> images -> gate -> test/guards   (테스트 실패가 publish 까지 전파)
초안    publish -> images -> lint                  (gate 가 경로에서 사라짐)
```

`push` 이벤트에서 테스트가 실패해도 `publish` 가 GHCR 에 `:latest` 를 올리게 된다.
D-016/L-016a 의 image promotion 계약이 걸린 자리다. 따라서 `publish` 에 `gate` 를 직접
물리는 것을 범위에 편입한다. 이 발견으로 등급이 S 에서 **M** 으로 올라갔다.

### V6·V7 이 buildx 전환의 조건을 정한다

`docker buildx` 의 `docker-container` 드라이버는 빌드 결과를 로컬 daemon 에 남기지 않는다.
`cache-to: type=gha` 는 그 드라이버를 요구하므로, `--load`(또는 `load: true`) 없이 바꾸면
뒤따르는 health smoke 와 `docker save` 가 **이미지를 찾지 못해** 실패한다.

### V9 가 검증 수단을 정한다

의존 그래프는 "존재한다"로 검증할 수 없다. `publish` 가 `gate` 에 도달 가능한지는 YAML 을
읽는 사람만 알 수 있고, 누가 `needs` 를 고쳐 끊어도 CI 는 초록으로 통과한다. 이 레포가
같은 종류의 성질(`dockerfile-module-sync`, `ci-test-matrix`, `gateway-exposure`)을 전부
lint + `--self-test` 로 고정해 온 것과 같은 자리다.

## 작업 항목

- [ ] **P1.** `images` 의 `needs` 를 `[lint, gate]` 에서 `[lint]` 로 완화한다.
      `gate` 는 `needs: [test, guards]` 와 `Propagate upstream failure` 를 그대로 두어
      최종 판정자 지위를 유지한다.

- [ ] **P2.** `publish` 의 `needs` 를 `images` 에서 `[images, gate]` 로 바꾼다.
      P1 이 끊은 간접 게이트를 직접 게이트로 복원한다. `if: github.event_name == 'push'` 는 유지.

- [ ] **P3.** `scripts/ci-release-gate-lint.sh` 신설.
      `ci.yml` 의 `needs` 를 읽어 `publish` 의 **전이 의존 폐포**를 계산하고 `gate` 가 그 안에
      있는지 검사한다. `--self-test` 로 조작 입력에서 실제로 실패하는지 고정한다
      (최소 4종: `publish.needs` 에서 gate 제거 · `gate.needs` 에서 test 제거 ·
      `Propagate upstream failure` 스텝 삭제 · `publish` 잡 자체 rename).

- [ ] **P4.** P3 을 `lint` 잡의 `Run CI policy lints` 에 `--self-test` 와 함께 추가한다.

- [ ] **P5.** `images` 의 `Build image` 를 buildx 로 전환한다.
      `docker/setup-buildx-action@v3` + `docker/build-push-action@v6`,
      `load: true`, `cache-from: type=gha,scope=<service>`,
      `cache-to: type=gha,mode=max,scope=<service>`.
      **scope 를 서비스별로 분리한다** — 매트릭스 6개가 단일 scope 를 공유하면 서로의 캐시를
      덮어써 마지막 하나만 남는다(Docker 공식 문서 경고). `--build-arg SERVICE` 는 유지한다.

- [ ] **P6.** `images`·`publish` 의 `matrix.service` 블록을 **한 글자도 건드리지 않는다**(V8).
      P5 는 `steps` 만 바꾼다. 변경 후 `image-contract-lint.sh` 가 6/6 을 그대로 추출하는지 확인.

## 검증 방법

각 행은 **실패를 주입한 뒤** 확인한다. "설정이 들어갔다"는 검증이 아니다.

| # | 대상 | 실패 주입 | 기대 |
|---|---|---|---|
| V-1 | P3 lint 본체 | `publish.needs` 에서 `gate` 제거 | lint exit != 0, 메시지가 끊긴 경로를 지목 |
| V-2 | P3 lint 본체 | `gate.needs` 에서 `test` 제거 | lint exit != 0 (폐포가 test 에 닿지 않음) |
| V-3 | P3 lint 본체 | `Propagate upstream failure` 스텝 삭제 | lint exit != 0 (도달 가능해도 전파가 없으면 게이트가 아니다) |
| V-4 | P3 lint 본체 | `publish` 잡 rename | lint exit != 0 (대상 부재를 통과로 읽지 않는다) |
| V-5 | `--self-test` | V-1~V-4 를 스크립트 내부 fixture 로 고정 | `--self-test` 단독 실행이 4종 전부 탐지 |
| V-6 | P5 buildx | `load: true` 를 뺀 상태로 실행 | `docker-health-smoke.sh` 가 이미지 부재로 실패 (V6 전제 재확인) |
| V-7 | P5 scope | 두 서비스가 같은 scope 를 쓰게 한 뒤 연속 실행 | 두 번째 빌드의 캐시 히트가 사라짐 |
| V-8 | P6 | `image-contract-lint.sh` 실행 | `images`·`publish` 양쪽 6/6 추출 유지 |
| V-9 | 전체 효과 | 변경 PR 의 실제 run 측정 | `images` 시작 시각이 `test` 완료 **이전**. 전체 벽시계 36분에서 20분 이하로 |

V-9 는 회귀 방지가 아니라 **목표 달성 확인**이다. 미달이면 원인을 적고 P1/P5 를 재판정한다.

## 미해결

- **`publish` 가 `e2e` 를 기다리지 않는다** (`ci.yml:473`, `needs: images`). `push` 에서 e2e 가
  아직 돌고 있거나 실패해도 GHCR 푸시가 나갈 수 있다. **이번 변경이 만든 것이 아니라 기존
  상태**이고, P2 의 `gate` 추가와는 별개 판단이다(e2e 는 `gate` 의 하류가 아니다).
  범위 밖으로 두되 D-033 에서 e2e 실행 정책을 정할 때 함께 본다.
- Gradle 빌드 캐시(`gradle.properties`, `setup-gradle`)는 표적인 컴파일 구간이 60초뿐이라
  이번 범위에 넣지 않는다 (ADR-0028 §후속 ③).

## 완료 조건

P1~P6 이 전부 체크되고, V-1~V-8 이 통과하며, V-9 의 실측값이 계획서에 기록된 상태.
