# D-065 actual collector adapter 감사

## 2026-10-21 — 계획 조사

- root가 PR210/211 Actions 대기 중 다음 무료 actualDB/Kafka collector 검증의 계획 조사를 허용했다. 자기 `/private/tmp/peakcart-worker-c-collector-adapter`, branch `debt/d065-collector-adapter`, base3bb01bb에서 plan만 작성한다.
- PR189 exact8cfdcb3 shell의 SQL/query/describe 인자 및 합성 adapter와 기존 e2e Compose 두 infra/init를 대조했다. 새 framework보다 기존 infra의 선택 기동과 좁은 소유 label/9092 overlay를 선택했다. 기존 source/신규파일은 아직 구현·실행하지 않았다.
- 별도 Codex 리뷰 미호출. actual case/CLI 형식과 실행 예산은 아직 미검증이며 P2부터P6를 완료로 표시하지 않는다. 운영 리소스/무료 외 비용/새 agent는 사용하지 않는다.

## 2026-10-21 — 승인된 구현과 Actions 준비

- root 승인 후 PR189 exact8cfdcb3을 자기 branch에 정상 merge했다. 기존 shell은 수정하지 않았다. 신규파일은 작은 Compose overlay, 단일 직접 runner/kubectl symlink adapter, hermetic/actual artifact 검사기, 별도 workflow다. 앱/PG는 기동하지 않고 기존 mysql+kafka/init만 재사용한다.
- overlay는 기존 unused external-control network도 internal로 제한하고 생성될 수 있는 모든 기존 volume/network에 nonce label을 붙인다. 다른 project를 재사용하지 않는다. 생성 전 intent, immutable container exec/remove, 중간 실패 후 always cleanup을 유지한다. 유한 timeout은 exit124와 부분stdout/stderr로 보존하며 credential-bearing argv를 출력하지 않는다.
- 로컬 hermetic5 methods 및 Python/YAML 구문·diff 검사가 통과했다. 실제11 collector case와 actual artifact9음성변이는 아직 미실행이다. workflow는 기존65+16 shell 대조도 실행한다. 실제 group 생성/reset/no-commit 할당/출력 형식과 runtime예산은 Actions 결과 전까지 검증되지 않았다. P2부터P6는 미완이다.

## 2026-10-21 — 첫 실제 실행 실패와 관측 보완

- dedicated39591368823, head8667537/merge checkoutb01b187: 기존 shell 자기검사와 새 hermetic5는 통과했다. actual runner는 218.3초 후 AssertionError로 실패했다. baseline부터 second-sample-missing까지 9case의 기대 exit/진단은 통과했지만 no-commit/restored는 미실행, complete=false다. artifact 검사/9변이는 건너뛰었다.
- 실제 SQL Unknown column, 양수 lag, 두 번째 describe 이전 실제 group 삭제까지 raw332files를 내려받았다. cleanup은 자기2container/1network/2volume 제거, complete=true이며 always cleanup도 성공했다. runtime331files는 evidence/D-065-collector-adapter/first-actions-39591368823.tar.gz와 개별 SHA256 manifest로 보존한다. upload가 symlink를 따라 복제한 bin/kubectl runner source 한 파일은 runtime이 아니므로 archive에서 제외하고 manifest에 기록했다. 원자료는 그대로 보존한다. 이 source 문자열 때문에 최종 artifact guard가 실패할 가능성도 발견해 cleanup에서 adapter symlink를 제거한다. 실제 비밀 조회/유출은 없고 공개 fixture account를 만드는 source다.
- 최초 자료에는 no-commit topic 생성/consumer 시작/member 조회의 출력이 없어 어느 단계인지 단정할 수 없다. 새 수정은 이 세 단계의 exit/stdout/stderr, 유한 member 조회 기록과 consumer log, 실패 stage만 보존한다. 실제 할당/CLI 결과를 합성하지 않으며 기존40초 할당·120초 consumer·collector240초 한도 및 운영 판정은 유지한다. 수정된5 hermetic 검사는 로컬 통과했으며 실제 수정 검증은 다음 Actions에서 수행한다.

## 2026-10-21 — 원 baseline coverage 정정과 stdin 반례

- 독립 reviewer가 최초 baseline calls16개 중 order-svc-dlq-group 두 표본이 누락됐음을 확인했다. 따라서 앞의9case는 기대 exit/진단 일치이며 완전한 baseline coverage PASS가 아니다. 최종 artifact verifier의5groups×2 조건은 그대로 유지한다. no-commit 준비 실패와의 인과는 아직 확인되지 않았다.
- 원 adapter는 input 없는 Kafka에도 docker exec -i와 상속 stdin을 사용해 실제 collector while-read가 소유한 다음 group 줄을 소비했다. 원 kubectl exec는 -i가 없으므로 adapter 의미 차이다. input 없는 subprocess는 DEVNULL, SQL input이 있는 exec만 -i를 사용하도록 수정했다. 제품 collector source는 불변이다.
- 실제 Bash while-read와 stdin을 읽는 Docker-like local executable을 연결한 회귀는 원2줄 의미를 복원하면 order-target만 남아 예상 실패한다. 수정후 두 그룹과 SQL input 보존을 포함한6methods가 통과했다. 임시 원형변이를 되돌린 source SHA256 및 원 실패/수정 성공 log를 stdin-pair-manifest.json으로 보존했다. 이는 hermetic 경계검사이고 실제5groups×2는 새 Actions에서 다시 확인한다.
- 원 일반 CI39591368029는 replay-entrypoint-lint의 fixture publication_status UPDATE2개에서 실패했다. 원 로그를 /private/tmp/pr212-ci39591368029-lint.log에 보존했다. 운영 SQL 허용으로 확대하지 않고 자기 fixture의 고정 두문장에 한정하는 guard 보완을 준비한다.

- root가 승인한 guard 보완은 정확한 scripts/replay-drain-docker.py 경로와 id1001의 두 고정 publication 상태 setup 문장 전체만 허용한다. SQL 문자열은 변경하지 않고 nonce/immutable-ID mysql 경계도 유지한다. 같은 SQL의 다른 path, 같은 path의 id변경/상태열변경을 self-test가 거부한다. 실제 전체 lint에 임시 다른path와 id변경을 주입한 두 음성도 REPLAY-ENTRY-003으로 실패했고 source 복원 후 정상 통과했다. 이 원형/full-lint 자료를 guard-negative-controls manifest/log로 보존했다. 기존 drift9와 운영 직접SQL 실패전파는 유지하며 guard source를 독립 리뷰 범위에 추가한다.

## 2026-10-21 — no-commit 실제 출력 전제 수정

- 진단head2efb/전용39592563904는 no-commit-member-assignment에서 실패했다. topic create와 detached consumer start는 실제 exit0이고 members row는 order-no-commit/consumer-id/#PARTITIONS=1/ASSIGNMENT=(0)이다. topic명 substring을 members 출력에서 찾던 전제가 맞지 않았다. consumer log는 비어있고 cleanup2container/1network/2volume은 통과했다. 원자료와 개별 hash를 diagnostic-actions-39592563904 bundle에 보존했다.
- 준비 판정은 실제 members의 해당 group 1partition과 별도 기본 describe의 정확한 group/topic/partition0/CURRENT-OFFSET '-'를 함께 요구하도록 바꿨다. 두 실제 stdout를 보존한다. topic 없는 members 출력만으로 성공하지 않으며 합성 '-' row로 실제 검증을 대체하지 않는다. 변경된 hermetic7 methods는 통과했고 실제 출력 조합은 새 Actions 검증 전이다. deadline40초와 운영 collector 판단은 유지한다.
- 최초9case와 두 번째9case 모두 exit/진단 일치만 기록하며, stdin 수정 전 baseline intake 누락이 있어 완전한 관측 PASS가 아니다. 다음 실제 run의5groups×2 및 최종 no-commit/restored/아티팩트 음성까지 확인해야 P2–P6 판단이 가능하다.
