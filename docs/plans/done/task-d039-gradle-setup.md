---
grade: S
---
# task-d039-gradle-setup

PR: [#157](https://github.com/Kimgyuilli/PeekCart/pull/157) (머지 2026-09-29)

## 명제
`gradle.properties` 신설 · `setup-gradle` 전환의 PR 시간 이득이 실측 없이 추정(ADR-0028 §후속 ③ "컴파일 60초")으로만
남아 있으면 미완이다. 착수 여부는 그 실측으로 판정한다.

## 작업 항목
- [x] P1. 최신 코드의 CI run 2개에서 Gradle 구간과 임계경로를 분해한다
- [x] P2. 판정을 TASKS 에 기록하고 구현 없이 닫는다(사용자 결정 2026-09-28)

## 실측
| run | 성격 | test 잡 종료 | 임계경로 종료(e2e negative-control) | test 여유 |
|---|---|---|---|---|
| 36422217146 (#156) | 스크립트만 변경 | 12:35:49 (order shard 285s) | 12:41:30 | 약 5.7분 |
| 36393965323 (#154) | Java 변경 | 07:57:09 (order shard 309s) | 08:04:22 | 약 7.2분 |

- **test 잡(setup-gradle 적용 대상)**: order shard 기준 Gradle 기동→`:test` 시작 50초(설정 26 · 컴파일/jar 24).
  임계경로 밖이라 줄여도 PR 시간은 0초 줄어든다. 절감되는 것은 러너 시간뿐이다
- **images 잡(임계경로 위, Java 변경 시)**: Gradle 은 Dockerfile 안 `bootJar` 에서 돈다. notification 기준
  35.6초 중 컴파일 약 8초, 나머지 약 28초는 `--no-daemon` 기동·설정이다. `setup-gradle` 캐시는 docker build
  안에 닿지 않고, `gradle.properties` 는 현재 Dockerfile 이 COPY 하지 않는다. 컴파일 8초가 표적의 상한이다
- 스크립트만 바뀐 PR 은 이미지 Gradle 레이어가 전부 CACHED 라 표적 자체가 없다

## 판정
PR 시간 이득 상한 ≈ 0초(test) · 수 초(images 컴파일 8초 중 일부). **하지 않는다.**
재개 조건: test 잡이 임계경로에 올라오거나(e2e 단축 등), 러너 시간 비용이 문제로 제기될 때.
이미지 빌드의 Gradle 기동·설정 28초는 `setup-gradle` 과 다른 수단(BuildKit cache mount 등)의 문제라 이 행의 범위가 아니다.

## 검증
판정 작업이라 코드 변경이 없다. 수치는 위 run id 의 jobs API 와 로그 타임스탬프로 재현된다.

## D-087 정정 (2026-10-21)

원 run36393965323/job108836245553 로그에서 bootJar35.6초와 gha cache export44.1초는 확인되지만,
compileJava/processResources/classes 헤더27.96초와 jar28.06초는 실제 태스크 시작 시각을 제공하지 않는다.
따라서 위의 컴파일8초/기동설정28초 분해와 그 값에 근거한 표적 상한/수단 분리는 철회한다.
setup-gradle 캐시가 Docker build에 연결되지 않는 구성과 당시 test 잡의 여유는 별개 근거다.
[후속 D-087](D-087.md)은 실제 CI 내부 --profile과 원 build record를 연결해 재측정한다.
새 실제 측정 전에는 대체 비중이나 최적화 결론을 확정하지 않는다. 원문은 역사 기록으로 보존한다.

### 2026-10-21 D-087 실제 profile 후속

원문은 위에 보존한다. run39589286318/branch24d4942/actualcheckout526ac935의6 images는 fresh Gradle profile과 exact BuildKit record/hash를 수집했다. notification은 Gradle total37.232초/startup3.802초/configuring2.677초/common:compileJava18.437초/bootJar task3.225초이며 BuildKit bootJar RUN37.6초/cache export20.0초는 별도 wall time이다. 이들을 합산해 임계경로나 최적화 상한으로 부르지 않는다. 원8/28분해는 지지되지 않는다. 모든6서비스의 원 HTML/log/record 및 수치·한계는 [D-087 evidence](../../evidence/D-087/README.md#실제-actions-측정-39589286318)에 있다. 일반 notification test의pretests GET429 실패 때문에 run전체성공으로 기록하지 않는다.
