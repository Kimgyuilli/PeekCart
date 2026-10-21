# D-065 로컬 constraint 안전성 실험

이 증거는 offline fixture snapshot classifier와 자기 소유 MySQL SQL 실험의 결과다. 운영 데이터의 provenance, live writer 부재, allocator counter 안전성, 실제 Flyway 이력/재개, 운영 적용 가능성을 증명하지 않는다. 보고서의 `rollout_ready`는 항상 false다.

## 재현

경량 입력/오류 출력 검증:

```bash
python3 scripts/tests/test_dlq_contract_preflight.py
```

Docker heavy 슬롯을 확보한 후 실행한다. 새 evidence 디렉터리만 허용하며 기존 DB/컨테이너에 접속하는 옵션은 없다.

```bash
python3 scripts/tests/dlq_contract_lab.py --evidence-dir /private/tmp/dlq-contract-new-run
```

lab은 MySQL 8.0.46, network none, host port 없음, tmpfs data를 사용한다. 네 서비스의 실제 migration SQL 파일을 버전 순서로 mysql CLI에서 적용하고 서비스와 같은 8개 schema 권한으로 backfill한다. **Flyway/JPA를 실행한 것은 아니다.** migration 파일 목록·source hash·이미지 ID·각 대조 결과를 evidence 디렉터리에 기록한다. 실패 여부와 무관하게 자기 컨테이너만 정리한다. 강제 중단에 대비한 별도 cleanup은 기록된 container ID/name/label을 모두 대조하며, 다른 컨테이너를 목록 필터로 일괄 삭제하지 않는다.

```bash
python3 scripts/tests/dlq_contract_lab.py --cleanup --evidence-dir /private/tmp/dlq-contract-new-run
```

`fixture-before.json`과 `fixture-provenance.json`은 lab이 직접 구성한 데이터의 예다. 보존할 때도 fixture 전용이다. 실제 원장 export나 토큰/개인 키/암호를 evidence에 추가하지 않는다. 독립 호출은 다음과 같으며, exit 0도 운영 전환 허가가 아니다.

```bash
python3 scripts/dlq_contract_preflight.py --snapshot /private/tmp/dlq-contract-new-run/fixture-before.json --evidence /private/tmp/dlq-contract-new-run/fixture-provenance.json
```

## 해석 한계

- 입력 JSON은 DB의 완전성·최신성을 독립 인증하지 않는다. hash는 특정 행/입력의 변경을 감지하며 역사적 생성 경로를 인증하지 않는다. live DB 조회는 lab의 자기 fixture export에만 있다.
- pre-transaction export hash 비교는 동시 writer를 막지 않는다. 별도 연결의 old writer가 backfill 트랜잭션 도중 NULL을 넣는 대조가 이를 보여준다.
- 최소 권한의 raw SQL 성공은 Flyway schema history, failed migration repair, restart 후 재개 성공을 뜻하지 않는다. 이 무료 검증은 후속 단계로 남는다.
- 첫 ALTER/둘째 실패 및 DML implicit commit은 SQL 트랜잭션 경계만 보여준다. 자동 nullable 복귀나 자동 Flyway repair 절차는 제공하지 않는다.
- allocator 예약 ID와 old AUTO_INCREMENT의 충돌 대조는 두 writer를 혼합하면 안 되는 반례다. 이 classifier가 allocator를 검사한다는 뜻은 아니다.
- 선행 PR201의 실제 JPA 71개 증거는 별도다. 여기서 raw SQL count/rollback을 관측했다고 Kafka ack나 application 트랜잭션까지 검증했다고 쓰지 않는다.

## CI

기존 lint job의 별도 named step이 경량 테스트와 lab을 실행한다. bash pipefail로 lab 실패를 tee가 숨기지 않게 하고, always cleanup 및 evidence upload를 별도 step으로 둔다. 이미지 tag/version과 실제 image ID를 기록하고 step timeout을 둔다. CI 결과는 로컬 결과와 별도로 확인한다.
