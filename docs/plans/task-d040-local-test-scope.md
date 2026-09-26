---
grade: S
---
# task-d040-local-test-scope

`work.md` §8 로컬 검증 범위 재평가 (D-040, ADR-0028 §후속 ④)

## 명제
5모듈 싱글톤 전환(D-032·D-035) 이후의 로컬 `./gradlew test` 전량 소요가 실측되지 않았거나,
그 수치로 §8 "전량 유지" 를 판정한 결과가 TASKS·progress 에 남아 있지 않으면 미완이다.

## 코드 확인 (착수 전)
- `.claude/commands/work.md:213` 은 `./gradlew test` 전량, `:33` 은 "등급이 줄이는 것은 리뷰이지 테스트가 아니다"
- 모듈별 `--rerun` 실측은 D-035 계획서에 있다: order 224 · product 156 · payment 55 · notification 69 · user 58초
- 전 모듈 전환 후 전량 실측은 없다. user 감사 파일의 263초는 user 만 전환된 시점이라 다른 모듈은 캐시 히트였을 가능성이 크다
- `gradle.properties` 가 없어 `org.gradle.parallel` 이 꺼져 있다. 모듈은 직렬로 돈다
- ~~`--rerun` 없는 `./gradlew test` 는 입력이 안 바뀐 모듈을 UP-TO-DATE 로 건너뛴다. §8 의 "전량" 은 실제로는 변경 영향 범위다~~
  **정정(P1 대조군)**: 서비스 5모듈은 해당하지 않는다. 클래스 순서 시드 기본값이 `System.currentTimeMillis()` 이고
  test 태스크 `systemProperty` 로 들어가(`user-service/build.gradle:73-83` 외 4모듈) 입력이 매 실행 바뀐다.
  UP-TO-DATE 로 건너뛰는 것은 common · gateway · peekcart-common-auth 뿐이다. 초안은 Gradle 기본 동작만 보고 빌드 스크립트를 확인하지 않았다

## 작업 항목
- [x] P1. main 기준 `./gradlew test --rerun` 전량 실측. 모듈별 소요와 테스트 수를 기록
- [x] P2. ~~한 모듈만 고친 상태에서 실행 범위 측정~~ → 변경 없는 `./gradlew test` 대조군으로 대체. 서비스 모듈이 변경과 무관하게 전부 돌므로 한 모듈 변경 실험은 같은 결과를 반복한다
- [x] P3. P1·P2 로 §8 전량 유지 여부를 판정. 유지면 `work.md` 는 고치지 않는다
- [x] P4. TASKS.md D-040 행과 PHASE5 progress 에 판정과 수치를 기록

## 검증
- P1 은 테스트 리포트 개수로 실제 실행을 확인한다. `--rerun` 을 빼고 돌려 전 모듈이 UP-TO-DATE 로 끝나는 것을 대조군으로 본다
- P2 는 고친 모듈과 그 의존 모듈만 `test` 가 실행되고 나머지는 UP-TO-DATE 인 것을 태스크 출력으로 확인한다

## 실측 (2026-09-27, 로컬)
| 실행 | 소요 | 실행된 test 태스크 |
|---|---|---|
| `./gradlew test --rerun` | 706초 | 8모듈 1253 테스트 0 실패 |
| `./gradlew test` (변경 없음) | 568초 | 서비스 5모듈. common · gateway · auth 는 UP-TO-DATE |

태스크 시작 간격(컴파일 포함, `--rerun`): common 31 · gateway 12 · notification 95 · order 286 · payment 95 · auth 2 · product 108 · user 61초.
D-032·D-035 전 서비스 5모듈 합계는 약 4510초(75분)였다.

## 미해결
- 병렬 실행(`org.gradle.parallel`) 도입은 D-039 범위다. 여기서는 다루지 않는다
- 서비스 test 태스크가 UP-TO-DATE 가 되지 않는 문제는 D-044 로 등록했다. 판정: §8 전량 유지
