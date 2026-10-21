# D-087 Docker build profile evidence

기준 source는 main bdc370a80e0bfa6adefb6a436a5f608f878d1b69다. 기존 images job의 실제 Docker bootJar 실행에 --profile을 추가하고 raw 자료를 보존한다. 제품 코드/최종 runtime stage의 jar·사용자·entrypoint와 gha mode=max 정책은 유지한다. task 헤더를 시작 시각으로 해석하지 않는다.

## 수집 연결

1. 실제 checkout SHA/Actions SHA·run/attempt/builder/runner 정보와 Dockerfile·wrapper hash를 build 직전에 기록한다.
2. runtime image build metadata의 정확 `buildx.build.ref`로 원 plain log와 `.dockerbuild`를 추출한다.
3. 같은 builder/동일 SERVICE의 `gradle-profile` scratch target으로 HTML과 그 RUN에서 생성된 service/start/end/hash metadata만 local export한다. runtime image에 report를 넣지 않는다.
4. export build의 bootJar vertex가 CACHED인지 확인한다. 다시 실행됐다면 원이미지와 분리된 자료라 거부한다. 원 runtime build가 CACHED라면 이전 report를 보존하지만 fresh Gradle 실행으로 집계하지 않는다.

목적별 Python collector/analyzer 하나와 selftest 하나다. 새 shell wrapper/설정 계층/외부 parser를 추가하지 않는다. 사용하지 않는 rawjson 이중 수집과 project-total 중복 집계는 제거했다. 기존 PyYAML 설치가 있는 CI policy lint에서 자기검증 두 줄을 실행하며 images에는 실제 수집만 둔다.

## 시간 해석

`analysis.json`은 Gradle summary/task 시간, 원 bootJar vertex, gha cache-export vertex를 별도로 기록한다. 단위는 seconds 문자열이다. Gradle row는 중첩/겹침이 있을 수 있고 BuildKit vertex도 별도 wall-time이므로 합산해 job/임계경로 시간이나 최적화 상한으로 부르지 않는다. UP-TO-DATE/FROM-CACHE 등의 task 결과도 함께 보존한다.

fresh인 경우 profile RUN 생성 시각이 현재 image build window 안에 있어야 한다. cache-hit은 이전 생성 시각으로 표시하며 `gradle_profile`을 null로 두고 이전 자료를 별도 필드에 보존한다. 누락/서비스 불일치/손상/중복 summary/잘못된 수치/불완전 vertex/hash 불일치/추출 때 재실행은 exit2다. 실패 때 이전 성공 `analysis.json`을 남기지 않으며 raw artifact는 always step에서 보존한다. 실제 fresh 관측이 없으면 미측정으로 남긴다.

## 검증 상태

```bash
python3 -B scripts/ci-build-profile-selftest.py
python3 -B scripts/ci-build-profile-selftest.py --mutants
```

첫 Actions39587257345(d0ec) lint의10 method/8 control은 통과했다. 이미지 build 뒤 collector의 full ref 인자 오류로6 images가 실패했고 실제 profile/export/health smoke는 미실행이다. 첫 수정 b1d2는 log/export/HTML 추출에 성공했지만 진행 로그의 stderr 출력 때문에 분석에 실패했다. 단일 progress stream 수정 후 전체10 method/10 wrong-implementation control은 새 Actions 실행 대상이다. `minimal.txt`는 정상 fresh/별도 export 시간의 최소 로컬1 method PASS이며 전체 통과 증거가 아니다. 로컬 Gradle/Docker 실행은 없다. exact CI policy body를 mock하는 정상/각 child rc23 대조도 전체 suite에 포함돼 새 fixture와 mutants의 실패 전파를 확인한다.

최종 수정 코드의 실제6서비스 profile은 아래 Actions 측정 절에 보존한다. 초기 구현/오류 source snapshot과 성공한 측정 source를 구분하며 전체 CI 성공은 별도로 확인한다.

## 원래 CI 근거

`original-images-notification.log.gz`는 run36393965323/job108836245553 원 로그 bytes를 그대로 gzip 보존한 것이다. raw SHA/bytes/관측값은 `original-source.json`에 있다. JWT/GitHub token/private-key 패턴 검출0을 확인한 뒤 보존했다. bootJar35.6초, common compileJava/processResources/classes27.96초·jar28.06초, gha cache export44.1초를 직접 확인했다. 실제 compile 시작은 없으므로8초/28초 분해를 지지하지 않는다. D039 archive는 원문 뒤에 좁은 정정만 추가했다.

## 확인한 primary source

- [Gradle8.14.4 profile HTML renderer](https://raw.githubusercontent.com/gradle/gradle/v8.14.4/platforms/core-runtime/build-profile/src/main/java/org/gradle/profile/ProfileReportRenderer.java): summary와 task 표의 직접 생성 source다.
- [Docker local exporter](https://docs.docker.com/build/exporters/local-tar/): scratch target의 filesystem만 추출하는 방식이다.
- [Buildx history logs](https://docs.docker.com/reference/cli/docker/buildx/history/logs/)와 [history export](https://docs.docker.com/reference/cli/docker/buildx/history/export/): 정확 build record를 보존한다.
- [build-push-action v6 source](https://raw.githubusercontent.com/docker/build-push-action/v6/src/main.ts): build metadata 및 record/summary 동작을 확인했다. ref는 실제 원 job metadata에도 있다.

별도 Codex 리뷰 미호출. 전체 CI와 정확 head 독립 검토는 대기이며 shared TASKS/PHASE/master는 root 소유다.

## 첫 실제 실행 실패와 수정

`first-attempt/manifest.json`은 d0ec/run39587257345/job119180029499의 원 notification log와 artifact bytes를 gzip/hash로 보존한다. credential 패턴 검출0을 확인했다. 원 이미지 build는 성공했지만 collector가 `builder/node/id` 전체를 CLI record ID로 넘겨 `no record found`로 실패했다. 같은 job의 action post는 `--builder <builder> <id>`로 정상 export했다. 이것은 계측 구현 오류이며 새 profile 측정은 아니다.

수정은 qualified ref의 builder/native record ID를 분리해 명시적으로 전달한다. 전체 ref는 증거에 그대로 보존하고 latest-record fallback은 없다. 기존 record method 안에서 image/export 성공 argv와 각각 logs/export rc23의 실패 전파를 검사하며, 원 오류를 되살리는9번째 mutant를 추가했다. 로컬은 이1method(5 paired cases)만 PASS이며 전체 suite/9mutants/실제6 images는 Actions에서 검증한다. 초기 `manifest.json`은 source_ref c5b33 시점의 기록이며 수정 source hash로 오해하지 않는다.

## 두 번째 실행의 progress stream 수정

`second-attempt/manifest.json`은 b1d2/run39588545903/job119183886821 원 job log와 전체 artifact(HTML/metadata/build record 포함)를 gzip/hash로 보존한다. 원 history 명령은 성공했으나 stdout은0 bytes, 정상 plain progress는 stderr에 있었다. HTML이 있어도 원 Actions analyzer는 `missing/duplicate bootJar vertex`로 실패했다.

collector는 stdout/stderr를 한 canonical progress log로 병합한다. 별도 fallback/중복 raw 파일은 새로 만들지 않는다. 성공 stub이 실제 형식의 progress를 stderr에 쓰도록 바꾸고 수집된 log 내용을 검증한다. stderr를 버리는10번째 mutant를 추가했다. 최소 로컬1 method/5cases PASS(0.934s), 전체 검증은 새 Actions에서 실행한다.

진단용 `/private/tmp/d087-second-adapted-progress` 복사에서 원 stdout+stderr bytes만 단일 log로 합친 뒤 수정 analyzer를 실행하면 notification `fresh_execution`으로 읽혔다. 이는 새 image 실행이나 원 b1d2 pipeline 성공이 아니며, 원자료를 재분석할 수 있다는 좁은 개발 확인이다. 다음 run의 cached 이전 profile을 새 fresh 측정으로 바꾸지 않는다.

## 실제 Actions 측정: 39589286318

측정 branch head는 `24d4942fb2e8c030f47ce8c12c24c3f2788a78f2`, 실제 PR merge checkout은 `526ac935ba8c404b056fb637ff10de68c36ce149`(base43+24d)다. Dockerfile/collector/selftest/workflow4파일은 두 SHA에서 byte동일하다. 각 서비스는 Linux x86_64/4CPU 별도 runner에서 Gradle8.14.4/JDK17로 실행됐다. nonce/no-cache 강제는 없었다.

6개 모두 `fresh_execution`이며 생성 origin이 현재 image window 안에 있었다. report 추출 build의 bootJar는 모두 CACHED였고 원 HTML hash와 연결됐다. 기존 runtime health smoke6개도 통과했다. `actions-39589286318/`는6개 원 artifact 전체와7개 image/lint job log, 별도 notification test 실패/현재 GET 가용성 자료를 gzip+raw/gzip hash로 보존한다. credential 패턴0, 원 analysis의6개 file-hash map 전체를 직접 대조했다. `summary.json`에 모든 task/result와 refs/origin/context가 있다.

단위는 초다. Gradle summary 표는 다음과 같다.

| service | Total | Startup | Settings/buildSrc | Loading | Configuring | Task Execution |
|---|---:|---:|---:|---:|---:|---:|
| gateway | 22.890 | 3.401 | 0.383 | 0.349 | 2.539 | 14.321 |
| notification-service | 37.232 | 3.802 | 0.318 | 0.325 | 2.677 | 26.899 |
| order-service | 28.262 | 2.801 | 0.245 | 0.281 | 2.198 | 20.108 |
| payment-service | 29.236 | 2.436 | 0.220 | 0.269 | 2.072 | 21.818 |
| product-service | 35.555 | 3.168 | 0.287 | 0.279 | 2.628 | 26.108 |
| user-service | 27.489 | 2.354 | 0.252 | 0.256 | 2.182 | 20.087 |

Artifact Transforms는6개 모두0초다. summary 항목을 더해 Total과 같다고 가정하지 않는다. task 행과 BuildKit wall time도 별도다.

| service | common:compileJava | service:compileJava | service:bootJar task | BuildKit bootJar RUN | gha cache export |
|---|---:|---:|---:|---:|---:|
| gateway | 해당 의존 없음 | 7.298 | 1.877 | 23.2 | 10.0 |
| notification-service | 18.437 | 1.022 | 3.225 | 37.6 | 20.0 |
| order-service | 13.065 | 1.135 | 2.640 | 29.9 | 20.8 |
| payment-service | 13.770 | 1.671 | 2.889 | 30.4 | 36.8 |
| product-service | 16.821 | 1.745 | 3.386 | 36.0 | 34.9 |
| user-service | 13.880 | 0.937 | 2.929 | 29.3 | 36.0 |

위 task는 모두 이번 실행의 빈 result(실행) 행이다. 다른 task의 Did No Work/NO-SOURCE 결과도 raw/JSON에 보존했다. gateway는 common 대신 internal-token-contract/peekcart-common-observability를 사용한다. 5개 domain의 common compile은13.065~18.437초이며 startup2.354~3.802초/configuring2.072~2.677초다. 원 태스크 헤더를 읽은8초/28초 주장을 이 측정으로 유지할 수 없다. 서로 다른 역사 source/환경의35.6초와 새37.6초를 개선/회귀 실험으로 비교하지 않는다.

현재 권고는 shared compile/cache 재사용 후보와 gha cache export 비용을 분리해 후속 실험하는 것이다. host setup-gradle만 추가해 Docker 내부 측정이 빨라진다는 근거는 없다. 새 설정/배포/성능 정책은 변경하지 않았다. 한 run의 별도6 runner 관측이며 반복 분포·warm/cold 인과·최적화 상한·전체 job 임계경로는 측정하지 않았다. Gradle phase/task/BuildKit 시간을 더하거나 빼서 그 값을 만들지 않는다.

정식 lint의10 methods(1.569초)/10 wrong-implementation controls가 실제 Actions에서 통과했다. 그러나 일반 notification test job119186128277은 Spring Security test6.5.9 POM GET429로 repository가 disabled된 뒤 bootJar/runtimeClasspath에서 실패했고 테스트는 시작하지 않았다. 11개 추가 실패도 동일 원인으로 보고됐다. 해당 POM은11:15:08UTC read-only GET200/2668bytes였고 dependency config는 base43/actual checkout에서 동일하다. 이것을 이전 PR206의 원인과 같다고 단정하지 않는다.

첫/두 번째 계측 자체 오류와 이번 일반 test429는 각각 보존한다. 최종 측정/evidence 문서 commit은 새 head를 만들므로 root 승인으로 오래된 head의 실패 job을 중복 재실행하지 않고 새 전체 Actions로 검증한다. 측정 source는 계속24d/526ac이며 문서 commit을 새 실측으로 부르지 않는다. 전체 CI/정확 final head 독립 검토는 별도 조건이다.
