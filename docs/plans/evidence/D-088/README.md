# D-088 evidence

기준 actual main은3bb01bbc이며 code snapshot은 source.json의c70c989다. inventory.json은 동적24파일과 literal run 소비자를 기록한다. CI23/inline selftest19/외부 health 대상4/ship-only writing1을 구분한다. 독립 oracle 결과가 아닌 source 확인용 유한 inventory다.

## 실행

```bash
python3 -B scripts/ci-lint-wiring-lint.py
python3 -B scripts/ci-lint-health-selftest.py
```

정식 실행은 Actions에서 한다. 외부 helper는 실제4개 shell을 임시 root/scripts에 복사하고 기존 기본 entrypoint를 정상/계약 위반 fixture로 실행한다. cluster apply/ssh/Docker/Gradle은 사용하지 않는다. namespace는 실제 kubectl kustomize의 local render다. 각 shell의 early-exit0/1과 wiring checker의 early-exit0/1, 총10poison이 fixture를 실패시키는지 별도 process로 확인한다. 기존19개 inline selftest는 배선만 검사하며 그19개 모두의 외부 early-exit 생존을 증명했다고 쓰지 않는다.

## 지원 범위

CI 검사는 YAML job/step의 지원 조건(default 또는 기존 !cancelled)과 literal direct bash/Python command로 한정한다. heredoc/multiline quote/선행 control·function·exit/disabled·continue-on-error 소비자는 근거로 세지 않는다. 임의 shell의 모든 실행 경로를 해석하는 framework가 아니다. 새 lint는 literal CI 소비자와 inline selftest 또는 별도 검토된 외부 대상이 필요하다.

writing-lint는 commit/PR 문체를 ship에서 검사하는 단일 예외이며 .claude/commands/ship.md fenced command의 실제 소비자를 요구한다. 전체 repository 문서를 CI 문체 검사하는 정책은 추가하지 않는다.

## 상태

minimal.txt는 최소 개발 확인만 기록한다. 정식6methods/10poison은 Actions 대기다. source.json의 hash는 code snapshot에 귀속하며 미실행 전체 검증 성공을 뜻하지 않는다. plan checkbox는 미검증 상태로 유지하고 사용자 Actions-first/draft 허용에 따라 draft PR에서 정식 CI를 시작한다. ship preflight blocked는 audit에 그대로 기록한다.

기존4개 shell은 byte불변이며 workflow delta는 기존 policy 앞의2줄뿐이다. shared TASKS/PHASE/master는 root 소유로 미편집이다. 별도 Codex 리뷰 미호출.
