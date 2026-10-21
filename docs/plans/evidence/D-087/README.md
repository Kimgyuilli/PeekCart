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

첫 Actions39587257345(d0ec) lint의10 method/8 control은 통과했다. 이미지 build 뒤 collector의 full ref 인자 오류로6 images가 실패했고 실제 profile/export/health smoke는 미실행이다. 수정 후 전체10 method/9 wrong-implementation control은 새 Actions 실행 대상이다. `minimal.txt`는 정상 fresh/별도 export 시간의 최소 로컬1 method PASS이며 전체 통과 증거가 아니다. 로컬 Gradle/Docker 실행은 없다. exact CI policy body를 mock하는 정상/각 child rc23 대조도 전체 suite에 포함돼 새 fixture와 mutants의 실패 전파를 확인한다.

현재 실제 새 CI profile은 아직 수집 전이다. 분석 결과/실행 SHA/6서비스의 fresh·cached 상태는 Actions 산출물을 읽은 뒤 추가하며, 지금 새 비중이나 최적화 결론을 확정하지 않는다.

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
