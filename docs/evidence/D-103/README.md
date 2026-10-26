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

## 숫자 정밀도 독립 검토 반영

초기 f328 source는 binary float 반올림으로 raw id `1.0000000000000001` 또는 line `1e-999`를 integer로 잘못 받아들였다. parser가 Decimal 값을 보존하고 렌더러는 정수값과의 정확한 동등성으로 판정한다. 독립 oracle은 Decimal 부호/계수 digits/exponent와 trailing zero로 별도 판정한 뒤 id/line만 작은 정수 placeholder로 치환해 jq의 float 손실을 피한다. 거대한 지수는 전개하지 않는다. 정상 `1.0`, `10e-1`, `1e999`, `1e1000000`은 정수 호환으로 보존하며 bool/분수/underflow는 거부한다.

현재 전체 대상은 신규9 method와 10 wrong-implementation control(렌더러9/oracle1)이다. renderer float 재도입과 oracle float 재도입을 각각 거부하는 control을 추가했다. 최소 로컬 numeric1 method PASS(1.634s), 별도 oracle1 method PASS(0.500s)만 추가 실행했다. 전체 suite/controls 및 새 정확 head Actions는 아직 대기다. 초기 manifest는 f328에 귀속하고 수정 source/최소 결과는 numeric-review-manifest.json으로 분리한다.
