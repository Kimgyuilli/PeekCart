# D-078 로컬 수집기 검증

기준 `af60cd6d9dceac541b6f457062093df70753f3ce`, 실행일 2026-10-21. D-078 전체 측정의 완료가 아니라 이름별 capture/collector의 부분 보완이다. 원래 8개 잔여와 무료 로컬 후속 가능 부분은 [계획](../../plans/done/D-078.md)에 남겼다.

## 출력 계약

이름은 product(상세), products(목록), productStock(재고)다. 기존 합산 표·배속·gate 뒤에 이름별 TSV 표를 추가한다. 계수 중앙값은 유효한 before/after 쌍만, 적중률 중앙값은 그중 hit+miss가 양수인 표본만 사용한다. 합산 ratio나 다른 이름의 표본을 가져오지 않는다.

| 열 | 의미 |
|---|---|
| valid_runs | 해당 이름의 네 counter가 유한·비음수 정수이며 차분이 역전되지 않은 런 |
| total_runs | 기존 collector의 공통 필터를 통과한 셀 런 수. 전체 파일 수와 다를 수 있음 |
| invalid_runs | 이름별 키 부분 누락, unavailable/비숫자/비유한/음수/소수 또는 counter reset 런 |
| uncollected_runs | 이름별 네 키가 모두 없는 역사적 metadata 런 |
| hitrate_runs | valid 중 hit+miss가 양수인 런. 0/0은 계수에만 유효 |

valid=0이면 `미수집`, 유효한 계수의 합이 모두 0이면 적중률만 `분모0`이다. invalid와 역사적 미수집은 각각 표의 수와 경고로 구분한다. runner는 Python 3을 사전 확인하고 이름별 counter를 표준 Decimal로 정확히 해석한다. 각 값과 합계는 IEEE double의 최대 유한값 이하이며 정수여야 한다. 1e-999나 긴 소수를 binary float로 반올림하지 않는다. runner의 이름별 capture는 scrape 실패·series 부재·오염을 문자열 `unavailable`로 TSV에 기록한다. collector는 이를 invalid로 제외하며 0으로 바꾸지 않는다.

기존 **합산** capture의 `awk s+0`, 합산 분모 0의 처리, gate 의미는 이번 변경에 포함하지 않았다. 이름별 fail-closed 처리가 기존 합산의 신뢰성까지 보증하지 않는다. 이름별 표본을 제외해도 독립적인 기존 합산 표·gate를 지우지 않는다.

## 재현

저장소 root에서 다음 명령만 실행했다. 운영 runner는 실행하지 않는다.

```sh
python3 -B loadtest/scripts/test_d026_collect.py -v
python3 -B docs/plans/evidence/D-078/check-legacy.py
python3 -B docs/plans/evidence/D-078/check-mutants.py
bash -n loadtest/scripts/d026-run-cell.sh
```

- [fixture 결과](fixture-results.txt): 9개 unittest 통과. 하위 입력에는 다른 캐시/결과 라벨, 실제 0, multiple series, 과거 누락, 부분 누락, hit/miss 각각 reset, blank/NaN/Infinity/negative/noninteger, 유효·무효·미수집 혼합, 0/0과 활동 표본을 포함한다. runner는 **cachegets/meta 함수 두 개만 추출**해 mock prom과 임시 TSV 파일로 실행한다. 전체 script를 source하지 않는다.
- [이전 코드 대조](legacy-results.txt): check-legacy.py가 위 immutable SHA의 collector를 git show로 읽고 18개 로컬 fixture에서 새 코드와 비교한다. 새 이름별 경고와 추가 표만 제외한 합산·배속·gate stdout이 byte 단위로 같다. 되돌림 detail +11.1% 실패도 보존한다. fixture 수치는 실제 부하 결과가 아니다.
- [변이 대조](mutant-results.txt): 임시 복사본에 product capture 누락, 빈 scrape 0 변환, 역사적 누락 0 변환, reset 허용, 0분모 적중률 포함을 주입해 각각 unittest 실패를 확인한다. 원본 파일은 변경하지 않는다.

전체 Gradle suite, 실제 GKE/loadgen/CPU 경합/부하/Billing/배포는 실행하지 않았다. 전체 PR CI 결과는 별도로 확인한다.

## 도구 위치 (2026-10-26, D-116 P3a)

이 폴더는 `docs/plans/evidence/D-078/` 에서 `docs/evidence/D-078/` 로 옮겼다. 위 명령의 `docs/plans/evidence/D-078/` 는 옮기기 전 경로이고, 지금은 `docs/evidence/D-078/` 로 바꿔 실행한다. 두 검사 도구의 저장소 루트 계산은 새 깊이(`parents[3]`)로 고쳤다.
