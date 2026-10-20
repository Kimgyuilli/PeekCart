# D-094 focused timing probes

기준 코드 `af60cd6d9dceac541b6f457062093df70753f3ce`. 아래 절차를 2026-10-21 로컬 Testcontainers에서 실행했다. 전체 Gradle suite나 실제 부하 측정과 구분한다.

공통 명령:

```sh
SPRING_PROFILES_ACTIVE=test ./gradlew :product-service:test --tests 'com.peekcart.product.infrastructure.ProductCacheFallbackIntegrationTest.unresponsiveRedis_isBoundedByCommandTimeout' --offline --no-daemon --no-parallel --max-workers=1 --console=plain --rerun-tasks
```

1. 원본 실행: 기존 3500ms bound와 product/productStock get·put 증가분 단언이 통과해야 한다. XML stdout의 observation에 commandTimeout=500ms가 있어야 한다.
2. [delay-1800ms.patch](delay-1800ms.patch): 측정 시작 뒤 getProduct 호출 전에 1800ms 대기만 추가한다. `commandTimeout=500ms`를 유지하고 같은 3500ms bound에서 실패해야 한다. 인위적 측정 구간 지연이며 실제 DB/JVM/부하 지연을 재현했다고 쓰지 않는다.
3. [timeout-1s.patch](timeout-1s.patch): 별도 실행에서 timeout test property만 1s로 바꾼다. `commandTimeout=1000ms`와 같은 bound 실패를 확인한다.
4. 각 patch는 공백-only context 줄을 피한 zero-context 형식이다. `git apply --unidiff-zero <patch>`로 적용하고 `git apply -R --unidiff-zero <patch>`로 복구한다. 각 변이는 다른 변이와 섞지 않고 원본 복원 후 실행한다. 끝난 뒤 원본을 다시 실행한다.

각 XML의 testcase/failure/observation을 보존하고, 예상 실패가 시간 bound의 AssertionError인지 확인한다. 기동·DB·assertion 경로 오류를 시간 변이 성공으로 세지 않는다. elapsed와 client config 및 fallback delta는 관측값이며 개별 명령의 실제 대기 시간이나 원인을 분해한 결과는 아니다.


## 실제 결과

| 실행 | Gradle 종료 | V3 결과 | elapsed (stdout ms) | client timeout | product get/put | productStock get/put |
|---|---:|---|---:|---:|---|---|
| baseline | 0 | 통과 | 2075 | 500ms | 1.0 / 1.0 | 1.0 / 1.0 |
| delay 1800ms | 1 | 예상 time-bound AssertionError | 3885 | 500ms | 1.0 / 1.0 | 1.0 / 1.0 |
| timeout 1s | 1 | 예상 time-bound AssertionError | 4070 | 1000ms | 1.0 / 1.0 | 1.0 / 1.0 |
| restored | 0 | 통과 | 2056 | 500ms | 1.0 / 1.0 | 1.0 / 1.0 |

각 실행은 선택한 testcase 1개, errors 0이었다. 두 negative의 failure message는 실제 Duration을 각각 `3.885825041S`, `4.070404417S`로 기록했다. 두 경우 모두 기존 `3.5S` 미만 단언에서 실패했으며 기동 실패를 negative 성공으로 세지 않았다.

[results.json](results.json)에 XML에서 추출한 observation, failure type/message, testcase 수와 source SHA-256을 저장했다. 각 실행의 전체 XML은 `/private/tmp/d094-results/{baseline,delay-1800ms,timeout-1s,restored}.xml`, Gradle 로그는 `/private/tmp/d094-{baseline,delay-1800ms,timeout-1s,restored}.log`에 보존했다. JSON은 이 비밀 없는 선택 필드만 담는다.

baseline과 restored의 테스트 source SHA-256은 모두 `f30b7a2cd8d107d4cf9b716cbf690eb0dace1003e88b108cd2c802715a31113f`다. 임시 mutation 두 개는 모두 제거했고 배포 설정의 500ms 및 3500ms bound는 바꾸지 않았다. 실제 부하, DB/JVM별 시간 기여, production 원인을 이 결과로 확정하지 않는다.

저장 patch는 임시 소스 복사본에 적용해 실제 실행한 두 mutation의 source SHA-256을 정확히 재현하는지 확인했다. 이 artifact 확인을 위해 Gradle timing probe를 반복하지 않았다.

## 원본 XML 보존

각 실행의 원본 XML byte를 변경하지 않고 gzip으로 보존했다: [baseline](baseline.xml.gz), [delay](delay-1800ms.xml.gz), [timeout](timeout-1s.xml.gz), [restored](restored.xml.gz). [SHA-256 manifest](xml-sha256.json)는 압축 전·후 해시와 크기를 모두 기록한다. `gzip -dc <file.xml.gz> | shasum -a 256`으로 원본 해시를 확인할 수 있다. 로컬 `/private/tmp/d094-results`의 XML과 압축 해제 byte 일치를 검사했다.

XML의 stdout/stderr와 failure를 확인했으며 fixture 외 비밀번호·token·credential은 없었다. 로그에는 로컬 hostname, Docker socket 경로, 임시 container ID와 localhost port 등 실행 환경 metadata가 포함되어 있다. 전체 Gradle 로그는 저장하지 않는다. 이 추가는 증거 보존이며 테스트를 재실행하지 않았다.
