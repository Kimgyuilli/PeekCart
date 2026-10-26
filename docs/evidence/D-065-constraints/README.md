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

lab은 MySQL 8.0.46, network none, host port 없음, tmpfs data를 사용한다. 네 서비스의 실제 migration SQL 파일을 버전 순서로 mysql CLI에서 적용하고 서비스와 같은 8개 schema 권한으로 backfill한다. **Flyway/JPA를 실행한 것은 아니다.** migration 파일 목록·source hash·이미지 ID·각 대조 결과를 evidence 디렉터리에 기록한다. 실패 여부와 무관하게 자기 컨테이너만 정리한다. Docker 호출 전에 name과 실행별 nonce를 원자적으로 기록한다. 생성 응답을 받지 못해 ID가 없어도 별도 cleanup이 정확한 name/fixture label/nonce를 대조한 뒤 조회된 immutable ID만 제거한다. ID 응답을 받은 경우에는 기록된 ID도 대조한다. 소유 검사에는 Python 최적화 옵션으로 제거되는 assert를 쓰지 않는다. 다른 컨테이너를 목록 필터로 일괄 삭제하지 않는다.

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

## cleanup 검토 정정

최초 f384 증거는 manifest 저장 **후** SIGKILL만 검사했다. [독립 P2 검토](https://github.com/Kimgyuilli/PeekCart/pull/202#issuecomment-6312033007)는 실제 Docker 생성 후 응답 전 중단 시 manifest가 없어 cleanup이 누락됨을 재현했다. 최상위 results/source-manifest/로그는 이 최초 실행의 역사적 증거로 유지한다. 수정 후 결과는 `cleanup-fix/`에 따로 보존하며 최초 head의 정리 경계가 충분했다고 주장하지 않는다.

수정 후 소유권 경량 검사는 CI에서 Python `-O`로 실행해 삭제 보호 검사가 최적화로 사라지지 않음을 확인한다. 아래 실제 Docker 대조는 로컬에서 실행했다. CI는 의도적 SIGKILL용 추가 컨테이너를 만들지 않으며 해당 두 Docker test methods를 명시적으로 skip한다.

```bash
python3 -O scripts/tests/test_dlq_contract_cleanup.py
python3 scripts/tests/test_dlq_contract_cleanup.py --docker
```

실제 대조는 생성 전 timeout, 실제 생성 후 응답 전 timeout, 생성 후 응답 전 SIGKILL, 같은 이름/다른 nonce 컨테이너 보존이다. 생성 결과 ID에 의존하지 않는 cleanup을 검사하며 foreign fixture도 테스트 자신이 만든 정확한 ID로만 마지막에 정리한다.

최종 수정 실행: SQL33 PASS/175.519초, 실제 cleanup7methods PASS/5.284초, optimized ownership5 PASS(Docker2methods skip), classifier10 PASS. cleanup-fix/source-manifest.json의56개 파일과 최종 실행 source가 일치한다. 최신 전체 CI/독립 P2 재검토는 별도 결과다.
