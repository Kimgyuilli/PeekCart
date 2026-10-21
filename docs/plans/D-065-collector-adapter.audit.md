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
