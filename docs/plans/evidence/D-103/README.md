# D-103 renderer 계약 근거

기준 source는 main f4ee03b97b0bec0c9be7cd3077911fb67b578b74다. `.claude/commands/plan.md`와 `work.md`는 각각 plan/diff schema를 생산자 output-schema로 사용한다. Codex/모델/API를 호출하지 않고 source만 읽었다.

`schema-history.json`은 git object에서 직접 읽은 초기3ce748b/선택속성268d994/required6026d5e/기준main의 완전 commit SHA, schema hash, 필수·선택 속성을 기록한다. 초기부터 run_id/summary/items와 공통 item5 필드가 필수이며 metadata를 새로 발명하지 않는다. generic renderer는 plan/diff의 known 속성 union을 받고 과거 선택 필드 생략을 허용한다. 현재 생산자 required schema는 수정하지 않는다.

`minimal.txt`는 item 내부/type/severity 원래 결함에 대한 최소 로컬2 method PASS다. 별도 jq oracle은 정형 빈 배열 정상/unknown P9 음성의 2개 최소 입력으로 개발 확인했다. 전체 기존 shell selftest와 신규8 method/8 wrong-implementation control은 Actions에서 실행하며 이 최소 기록을 전체 통과로 대신하지 않는다. shell syntax/Python AST/whitespace도 확인했다.

기존 CI의 Run CI policy lints가 shell selftest를 실행하고, 그 wrapper가 새 contract helper와 mutants를 실행해 실패를 전파한다. exact YAML step의 renderer 실패rc23/정상 paired mock도 신규8 method에 포함한다. 다른 lints/실제 명령은 mock으로 대체하며 runner 형태의 bash -e -o pipefail에서 downstream 중단을 확인한다. CLI 결과 검증과 CI 배선 검증은 모델 실행 증명이 아니다.

```bash
bash scripts/codex-review-render-selftest.sh
python3 -B scripts/codex-review-render-contract-selftest.py
python3 -B scripts/codex-review-render-contract-selftest.py --mutants
```

기존 shell selftest의 합성 자료에도 독립 jq oracle을 실행한다. 실제 `.cache/codex-reviews` corpus가 own/root tree에 없어 전체 과거 이력 회귀는 검증하지 못한다. V9는 원자료 없음을 skip으로 표시한다. 합성 자료로 과거282개/1267항목 등의 모집단을 재현했다고 주장하지 않으며 그 집계 사실 정정은 D-104 별도 작업이다. Gradle/Docker/운영/외부모델 요청은 없다. 별도 Codex 리뷰 미호출.
