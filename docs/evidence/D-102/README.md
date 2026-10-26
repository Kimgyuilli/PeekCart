# D-102 주석 검증 근거

기준 코드는 `be535c57ced11acfcbe9a24a349ddb6ad2055d8a`다. 대상은 모듈의 `src/test/**/*.java` 204파일이며 `src/testFixtures`·main·SQL·YAML·스크립트 및 DisplayName/assertion 등 실행 문자열은 제외했다.

초기 broad comment 검색은 219줄/113파일을 반환했다. GW/GP, review round, N/B/C/R/U 항목, 출처 없는 절 번호까지 보충한 [후보 inventory](comment-inventory.tsv)는 247줄/117파일이다. 문자열·문자·text block은 검색하지 않는다. 후보 수는 부채 수가 아니다. 일반 용어인 `실행계획`, ADR이 소유한 표면 번호, 파일 안에서 의미가 정의된 시나리오도 검색된다.

- 후보 235행의 주석을 보정했고 12행은 구체 출처·로컬 시나리오·일반 용어로 보존했다. before/after와 보존 근거를 각 행에 남겼다.
- 최종 변경 파일은 Java test 116개다. 후보 전체 117파일 중 GatewayObservabilityMetricsIntegrationTest는 ADR-0009가 정의한 P1-D를 사용하므로 그대로다.
- 최종 broad scan의 20개 적중은 [보존 근거](../../../scripts/test-comments/accepted-candidates.tsv)에 전부 기록했다. 기존에 보존된 12개뿐 아니라 풀어 쓴 뒤 검색에 남는 유효한 ADR·runbook 참조 등을 포함한다.
- 후보 밖의 문맥도 읽어 기존 4서비스 복제본이라는 설명은 공유 구현의 서비스별 배선 검사로, 없는 replay 진입점이라는 설명은 실제 테스트 seam/fixture 경계로 고쳤다. 기존 ADR·D-/L- 식별자는 파일별로 모두 보존했다.

재현 명령은 저장소 루트에서 실행한다.

```sh
python3 scripts/test-comments/check.py
python3 scripts/test-comments/check.py --scan
python3 scripts/test-comments/check.py --inventory /tmp/d102-inventory.tsv
```

JDK 17의 `java`/`javac`가 필요하다. 기본 실행은 저장소 내 JavaTokenScanner.java를 임시 디렉터리에 컴파일하고 JDK 17 compiler ScannerFactory가 반환한 token kind와 원문 spelling을 비교한다. compiler가 건너뛴 토큰 사이 구간만 comment 후보로 검색하므로 문자열/text block 경계도 compiler가 결정한다. handwritten Java lexer를 증거로 쓰지 않는다. JDK 17 이외 버전이나 lexical error는 실패 처리한다.

기본 실행은 기준 코드와 현재 Java 파일 집합 및 실행 토큰 열을 비교한다. 주석 변경 PR의 재현 검사이므로 다른 기능 변경이 합쳐진 미래 main에서는 그 변경도 차이로 보고한다. `--scan`은 baseline과 독립적으로 현재 comment 후보를 보여준다. `--inventory`의 line mapping은 이 PR에서 줄 수가 보존된 경우에만 허용하며 TSV의 before/after를 재현한다(수동 처분 사유는 committed TSV를 참조).

기본 실행 결과:

```text
PASS: 204 Java test files; 116 changed files; exact compiler tokens and existing source IDs preserved (superset); 9 injected controls
```

기존 5개 대조군(assertion 숫자, comment-only 변경, 새 opaque comment, qualified ADR/debt, 문자열/plain text block)에 4개를 더했다. `a + ++b`와 `a++ + b`를 구분하고, line comment의 Unicode escape newline이 새 필드를 활성화하는 변이를 잡고, escaped triple quote를 포함한 text block의 내용 변이를 구분하며 그 내용이 comment 후보로 나오지 않는지 확인한다. 기존 source ID는 파일별 기존 집합이 최종 집합의 부분집합인지 확인하며 집합 동등성을 주장하지 않는다(예: 유용한 ADR-0021 출처를 추가했다). 전체 Gradle test는 로컬에서 실행하지 않았으며 PR의 전체 CI를 별도로 확인한다.

C의 merchant 계약 frozen head `651044aa669032cf4fdecbaefcc3c58208ddcef7`와 비교했다. ApprovalLedgerIntegrationTest의 C 변경은 beginApproval 인자(192행 이후), D-102는 33/37행 주석이며 서로 다르다. TossPaymentClientTest의 D-102 변경도 31행 주석뿐이다. 해당 구현 커밋은 이 branch에 포함하지 않았다.

초기 regex 검증기는 operator 경계와 Java Unicode 전처리 및 escaped text block에서 false-green이 확인돼 교체했다. compiler 기반 재검사에서도 committed inventory의 247개 before/after와 최종 accepted 20행이 정확히 일치했다. 실제 Java test comment diff는 이 검증기 보완에서 바꾸지 않았다.

## main 201 통합 대조 (2026-10-21)

새 비교 base는 `4a4a36a12a3d914d6bc0379ded94bc0123c4d370`이다. 그 사이 추가된 기능/테스트를 원래 be535와 같다고 주장하지 않는다. 원래 204파일/247 inventory/20 accepted 결과는 당시 population 기록이다. 아래 별도 명령은 현재 전체 208 Java test 파일, main 대비 주석 변경 116파일의 token kind/raw spelling 및 기존 source ID 부분집합을 확인한다.

```sh
python3 -B scripts/test-comments/check-integration.py --base 4a4a36a12a3d914d6bc0379ded94bc0123c4d370
```

원래 새로 풀어 쓴 comment 줄도 각각 원래 클래스에 남아 있는지 확인한다. 이 추가 확인은 줄 존재 확인이며 중복 개수·의미의 독립 oracle이 아니다. 결과는 [integration-results.txt](integration-results.txt)에 보존했다. 현재 broad scan은 여전히 20개이고 기존 처분 근거와 같다.

수동 충돌은 ProductCacheFallbackIntegrationTest의 V3 fallback assertion 블록 한 곳이다. main의 D-094 timeout/경과/4delta observation과 실행 assertion 전부를 보존하고 누적값이 이전 테스트 잔여로 통과할 수 있다는 유용한 comment를 유지했다. ApprovalLedgerIntegrationTest, TossPaymentClientTest, DlqReplayCorrelationIntegrationTest는 자동 통합됐으며 이들도 전체 token/source-ID 대조에 포함된다. 원래 독립 review를 새 head의 승인으로 간주하지 않으며 정확한 통합 head 재검토와 전체 CI를 기다린다. Gradle/Docker 재실행은 없다.

## 도구 위치 (2026-10-26, D-116 P2)

규약(`docs/conventions/test-comments.md`)이 실행하는 `check.py` 를 `JavaTokenScanner.java`·`check-integration.py`·`accepted-candidates.tsv` 와 함께 `scripts/test-comments/` 로 옮겼다. 위 명령과 보존 근거 링크는 새 경로다. 기본 실행과 `check-integration.py` 는 고정 base 를 재현하므로, 그 뒤 Java test 파일이 바뀐 main 에서는 `JAVA TEST FILE ADDED OR REMOVED` 로 끝난다.
