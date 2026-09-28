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
