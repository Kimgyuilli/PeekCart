---
grade: M
---
# D-087 profile export cache 후속

## 1. 명제와 코드 전제

runtime이 remote cache의 bootJar를 재사용한 뒤 profile export가 같은 vertex를 다시 실행하면 detached 측정이다. 이를 image 측정으로 수용하거나 analyzer guard를 삭제하면 미완이다.

| 직접 근거 | 관측 |
|---|---|
| base | main209 c9e12d695e21c348a82da78c6577eac7b2dd4d89 |
| PR214 run39605979097/user job119243065091 | runtime CLI는 cache-from type=gha,scope=image-user-service 및 #33CACHED. 같은 builder의 gradle-profile CLI는 cache-from이 없고 #33DONE36.2s(Gradle bootJar 로그35.92s)로 재실행했다. analyzer exit2 detached 거부가 타당하다. |
| source | ci.yml runtime만 service별 gha cache-from/cache-to가 있고 export에는 없다. 같은 builder라는 전제만으로 remote-cache provenance가 재사용되지 않았다. |

Root 승인 좁은 M 범위: export에 runtime과 동일 cache-from1줄, 기존 workflow selftest의 동일성 및 누락/다른scope 음성만 추가한다. analyzer/제품/캐시쓰기·bust 설정은 변경하지 않는다. 새 ADR은 없고 기존 D087 실측은 당시 조건의 관측으로 유지한다.

## 2. 작업

- [ ] P1. export에 기존 service scope cache-from을 추가하고 기존 selftest/잘못된구현 대조를 확장한다. cached_previous_profile/fresh_execution 및 detached-rebuild guard는 그대로다.
- [ ] P2. 원 PR189/214 실패 원자료를 보존하고 정확 head Actions에서 실제6image runtime/export cache 상태와 분석 결과를 확인한다. 첫실패를 재실행성공으로 덮지 않는다. 로컬 full suite/Gradle/Docker는 실행하지 않는다.

## 3. 검증

경량 실제workflow contract 한 case와기존detached negative만 로컬 확인한다. 정식 기존selftests/10기존+2추가 mutants 및 실제6image는Actions에서 검증한다. 새 cache-from도 재사용을 보장한다고 추정하지 않으며 실제 export가 다시 실행되면 guard실패를 유지한다. shared TASKS/PHASE/master 미편집. 별도 Codex 리뷰 미호출.
