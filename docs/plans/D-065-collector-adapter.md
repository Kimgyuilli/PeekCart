---
grade: M
---
# D-065 actual DB/Kafka collector adapter 검증

## 1. 명제와 현재 증거

실제 `replay-drain-preflight.sh` 정상 진입점이 MySQL 결과와 Kafka CLI stdout/exit를 받아 판정하지 않으면 미완이다. DB/Kafka 결과를 미리 만든 문자열로 바꿔 통과하거나 실제 Kubernetes/운영 drain 증거로 확대해도 미완이다.

현재 기준 actual main3bb01bbc3190a9aa38a7b4e6faec7bb767117808이다. 선행 PR189 exact8cfdcb3f4a9dad7979c21ec84ee0beed9eacb7ad의 shell과 PATH fixture를 읽었다. 그 PR의65 collector/16 judge/9 drift 대조는 합성 adapter 증거이며 actual MySQL/Kafka 실행은 아니다. 이 후속은 선행을 명시하고 정상 merge한 자기 branch에서만 구현한다. PR189보다 먼저 병합하지 않는다. PR210/211 actual signed 실패와 리뷰 수정이 우선이다.

## 2. 코드 전제와 재사용

| 원문 | 확인한 경계 |
|---|---|
| `scripts/replay-drain-preflight.sh` (PR189) | 네 schema 집계 SELECT와 DISTINCT target group, 각 intake group을 수집한다. actual Kafka describe header/행 수치/동일 group/중복 partition 검사를 거쳐 두 표본을 판정한다. 날짜는 DATETIME(6)의 UNIX_TIMESTAMP 소수초다. |
| `scripts/tests/fixtures/replay-drain-kubectl.py` | 정확한 kubectl namespace/secret/exec 인자를 검증하지만 출력은 합성이다. real kubectl로 위임하지 않는다. 이 분기 계약을 실제 Docker exec로 연결하는 얇은 test adapter가 필요하다. |
| `docker-compose.e2e.yml` | MySQL8.0.46/Kafka3.8.1 서비스, internal network, host port 없음, root 공개fixture 자격증명, 별도 project와 fresh volumes를 이미 제공한다. 선택 기동 `mysql kafka`는 앱·PG stub·외부 canary를 요구하지 않는다. |
| `scripts/e2e/mysql-init/01-init-databases.sql` | 네 리소스 포함 자기 DB/account와8권한을 준비한다. 기존 init을 재사용한다. 실제 migration 원문을 서비스별 버전순 mysql CLI에 적용하고 hash를 기록하되 Flyway 이력 검증으로 주장하지 않는다. |

별도 generic lab framework, 새 Java 서비스, Kafka client 라이브러리, 앱 이미지 빌드는 추가하지 않는다. 기존 Compose의 두 서비스만 사용하고 좁은 override로 실행 nonce 소유 label 및 Kafka의 collector 기대 localhost9092를 맞춘다. 새 설정 생성 계층 대신 작은 정적 overlay를 둔다. 실제 Kafka의 producer/groups CLI와 mysql client를 사용하며 kubectl shim만 fixture 경계다.

## 3. 범위와 보안 경계

파일은 작은 Compose overlay, collector Docker adapter/직접 scenario runner와 필요한 hermetic controls, 별도 read-only Actions workflow, 이 plan/audit/evidence로 제한한다. 기존 ci.yml/saga/auth fixture는 수정하지 않는다. root 소유 TASKS/progress/master는 수정하지 않는다. 기존 collector 제품 변경이 필요하면 실제 반례와 최소 delta를 먼저 보고한다. 보안/데이터 모델 결정 변경이 없으므로 현재 신규 ADR은 필요 없다.

fixture는 생성 전에 random project+nonce ownership intent를 저장한다. 모든 exec/remove는 자기 immutable container ID와 project/nonce label을 확인한다. 기존 이름 충돌은 재사용하지 않고 실패한다. network/volume도 자기 label을 대조하며 다른 project는 지우지 않는다. subprocess/Compose wait/workflow는 유한하고 cleanup은 원 실패를 덮지 않는다. Secret 조회는 자기 공개fixture 계정의 base64 값만 반환하며 실제 kubeconfig/Secret/운영 DB를 읽지 않는다. argv/password를 로그에 기록하지 않고 실제 SQL 결과/describe 출력/exit와 민감정보 없는 호출 분류만 보존한다.

운영 replay는 열지 않는다. 이 DB/Kafka는 Actions의 완전한 폐기 fixture이고 실제 PG/결제/GKE/운영표본은 없다. DRAIN_LAG_RECHECK_SECONDS=0은 시간 대기만 줄인다. 두 실제 describe 호출과 원 collector의 판정/exit는 유지하며 실제 서비스 소비 완료나 배포 안전 보증으로 확대하지 않는다.

## 4. 작업과 실제 반례

- [x] P1. PR189 shell/fixture와 기존 Compose/init 경계를 읽고 재사용·의존·증거 한계를 정한다.
- [ ] P2. 선행189를 자기 branch에 명시적으로 통합하고 두 실제 컨테이너/원문 schema/bootstrap/그룹 offset fixture를 만든다. 실제 Kafka CLI로 group offsets를 생성·대조하고 command 실패를 성공으로 바꾸지 않는다.
- [ ] P3. actual 정상 기준을 만든다. 네 서비스 DB집계, 각 intake 및 최소 하나의 실제 target group이 두 번 관측되고 정상 drain exit0이어야 한다. 여러 partition의 actual lag 합계와 DATETIME(6) 소수초 출력도 원문으로 보존한다.
- [ ] P4. actual 단독 반례를 실행한다. REPLAY/PENDING, ledger REQUESTED, 최근 settled, 실제 positive lag, 실제 missing group 및 no committed offset, aggregate 성공 후 DISTINCT group SELECT 실패, 두 번째 표본에서 group 소실/조회 실패를 각각 원 조건/진단으로 확인한다. 복구 후 exit0을 다시 확인한다. 기존 합성 malformed/NaN/header-only 대조를 actual Kafka가 생성한 결과라고 부르지 않는다.
- [ ] P5. source/원문 migration/서비스·group·두 표본·case 누락은 green이 되지 않게 검사한다. 준비 전/후 실패·foreign collision·cleanup·로그 비밀값 대조를 구분해 보존한다.
- [ ] P6. Actions actual 결과와 전체CI/독립검토를 최종head로 확인한다. 새 actual Kafka 출력형식이 예상과 다르면 원문을 보존하고 최소 수정 후 다시 검증한다.

시나리오는 broker를 새로 반복 기동하지 않고 같은 자기 두 컨테이너에서 자기 데이터를 복구한다. Kafka offset 준비는 실제 CLI의 반환값과 describe로 확인하며 무조건 sleep/retry 통과를 도입하지 않는다. no-commit 상태는 auto-commit=false의 자기 console consumer로 만들고 할당 여부를 bounded CLI 관측으로 확인한다. second-sample 소실은 첫 describe 완료 뒤 자기 group을 실제 삭제하는 fixture hook으로 만들며 출력 합성을 하지 않는다. DB target-query 실패는 자기 fixture 열을 잠시 rename해 실제 SQL 오류를 만들고 원문대로 복구한다.

소수초 exact 1마이크로초 경계는 PR189의 결정적 judge fixture가 소유한다. 새 실제 DB 검증은 raw UNIX_TIMESTAMP(DATETIME(6)) 소수부와 충분히 오래된/최근 값의 수집 판정을 확인한다. 실제 실행시각을 고정한 합성 date를 DB/Kafka actual 증거에 섞지 않는다. 테스트는 Actions 우선이며 로컬 Gradle/Docker 전체 실행은 하지 않는다. runtime 예산은 CLI 호출 수와 실제 소요를 기록해 결정하고 production timing/판정 조건은 바꾸지 않는다.

## 5. 완료 후에도 남는 범위

이 결과는 Docker에 연결한 kubectl adapter 계약이다. Kubernetes auth/exec/Secret decoding 권한, GKE ACL/실제 service group 좌표, 실제 broker 운영 데이터/소비자 상한, P22 production timestamp 표본, operator 직접 apply 우회와 운영 rollout은 별도 증거다. PR189의 shell fix나 이 actual adapter가 D065 전체를 완료하지 않는다. 미병합 선행이 main에 들어오면 실제 main 정렬/작성 delta/새 CI를 다시 확인한다.
