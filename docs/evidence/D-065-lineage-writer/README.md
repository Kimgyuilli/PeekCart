# D-065 lineage writer 로컬 증거

실제 운영 데이터나 운영 전환 증거가 아니다. 전체 CI와 독립 검토는 PR에서 별도로 확인한다.

- `sql-prototype.py`는 별도 disposable MySQL 8.0.46 설계 실험의 당시 실행본이다. Docker network 내부 host `mysql`, `/probe/state.json`, 공개 테스트 비밀번호 `prototype-only`를 사용하는 임시 fixture다. 운영 환경이나 기존 DB에서 실행하지 않는다. 실행 환경 및 반증은 [audit](../../plans/done/D-065-lineage-writer.audit.md)에 기록했다.
- SQL prototype의 no-op upsert+ID 비교는 최종 writer 계약이 아니다. 같은 ID·좌표 재사용 반례가 추가로 발견되어 ADR-0043 Update Log의 실제 attempt 증가 count와 ID/점유 동시 대조로 보완했다.
- `sql-prototype.log.gz`는 12개 설계 대조, `sql-prototype-restart.log.gz`는 정상 restart 후 counter 보존이다. 정상 restart를 crash recovery 증거로 확대하지 않는다.
- `final-focused.log.gz`는 common unit 4개 및 실제 Spring/MySQL 67개 실행이다. `allocator-jpa.xml.gz`는 신규 15개와 SQL/JPA 오류 대조의 원본 XML이다. 최종 구현의 같은 ID 재사용, overflow/NULL, rollback/retry 및 current read 증거는 이 실행과 저장소 테스트 코드가 제공한다.
- `manifest.json`은 원본 비압축 해시와 실제 테스트 집계를 보존한다. `gzip -cd <파일>`로 원본을 확인할 수 있다. 테스트 소유 컨테이너는 종료했고 기존 momens 3개는 유지했다.
