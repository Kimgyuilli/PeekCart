# main GHCR 부분 publish 관측과 복구

각 서비스의 `:<expected SHA>` manifest/config와 **그 서비스의** `:latest`를 대조한다. 서로 다른6개 이미지 digest가 같아야 한다는 뜻이 아니다. 기존 [ADR-0030](../adr/0030-ci-final-gate-and-image-promotion.md)의 검증 artifact/원격 digest 승격과6레포 비원자성은 유지한다.

## 상태 읽기

main의 기존 gate/publish 뒤 `publication-state`가 read-only로 실행된다. partial publish 실패도 관측하고, 결과가 확인되지 않으면 job/전체 workflow가 실패한다. `publication-state-attempt-N`과 서비스별 `publish-evidence-<service>-attempt-N` artifact를 읽는다. 이들은14일 보존되며 attempt간 덮어쓰지 않는다.

- `aligned`: 해당 서비스의 SHA manifest와 latest digest가 같고, SHA manifest config가 기존 artifact의 검증된 image ID와 같다. load 검증 성공 marker도 필요하다.
- `different_latest`: SHA/config를 확인했지만 latest가 다른 manifest다.
- `unavailable`: marker/원 image ID/검증/조회/파싱을 확인하지 못했다.0이나 성공으로 읽지 않는다.
- 집계 `partial_relative_to_expected`는 관측 구간에서 일부가 aligned이고 다른 서비스가 different_latest인 경우다. unknown은 confirmed partial로 바꾸지 않는다.
- `stale_or_concurrent_release`는 시작/끝 currentmain이 expected SHA와 다른 경우다. currentmain 조회 실패나 unavailable/알 수 없는 추가 artifact는 `incomplete_observation`이다.

서비스별 읽기는 순차적이다. 시작·끝/각 읽기 시각을 확인하며 동일 순간의 원자적 snapshot으로 해석하지 않는다. currentmain이 같아도 다른 publisher가 전혀 없음을 증명하지 않는다. 각 receipt의 run/attempt와 현재 실행 목록을 함께 읽는다. `aligned`는 원 publish job의 실패를 취소하지 않으며 원 workflow가 초록이라는 뜻도 아니다.

## 실패 원자료와 재실행 전 확인

원 run/job/attempt의 로그와 artifact를 먼저 보존한다. 기존 `ghcr-push.log`는 stdout/stderr와 pipefail 실패를 보존한다. load/push outcome, expected SHA/service/run/attempt/image ID, 있으면 promotion.json을 함께 확인한다. stage가 다른 실패를 같은 unknown blob 원인으로 묶지 않는다.

```bash
gh run view "$RUN_ID" --json headSha,event,status,conclusion,jobs
gh api repos/Kimgyuilli/PeekCart/git/ref/heads/main --jq .object.sha
gh run list --branch main --event push --limit 10 --json databaseId,headSha,status,createdAt
gh api "repos/Kimgyuilli/PeekCart/actions/runs/$RUN_ID/artifacts" --paginate
```

원 event가 main push이고 expected SHA가 현재 main인지 확인한다. 그 뒤 시작한 release/publisher가 있거나 실행 중이면 오래된 run을 다시 써서 최신 태그를 되돌리지 않는다. 이미 승인된 세션의 정상 CI/기존 main GHCR 발행 범위에서 원인에 맞는 실행 경로를 선택한다. 이 문서는 운영 배포를 추가하지 않는다.

원 `image-<service>` payload/checksum/ID artifact는 **1일** 보존이다. 현재 API에서 가용하고 만료되지 않았는지 확인한다.14일 남는 tiny receipt는 payload를 복원하지 못한다. payload가 없으면 publisher 재실행 경로를 사용하지 않고 새 정상 검증 경로가 필요하다. publisher 안에서 이미지를 다시 빌드하거나 다른 source/artifact로 바꾸지 않는다. 이 세션에서 승인된 정상 CI를 위해 추가 사용자 승인을 새로 요구하지 않는다.

조건이 맞고 실제 실패 원인상 재실행이 타당하면 실패 publisher job만 재실행한다. 자동 retry는 추가하지 않는다. 원 SHA/ref가 유지되는 GitHub 동작과 별개로 현재main/새release/원artifact 수명을 직접 확인한다.

```bash
gh run rerun --job "$FAILED_PUBLISH_JOB_ID"
```

성공5개가 attempt1, 실패1개가 attempt2여도 현재run/같은SHA의 marker가 유효하면 정상 입력이다. 서비스별최신attempt를선택하지만최신후보가틀렸다고과거성공으로fallback하지않는다. 이전실패artifact는지우거나덮지않는다.

## 재실행 뒤 확인

6개 publish job과 새 `publication-state` 결과를 확인한다. observer가 자동으로 다시 실행됐다고 가정하지 않는다. 재시도 뒤의 observer가 없다면 읽기 전용 observer job만 재실행한다. 원 publisher 전체를 재실행하는 대체 경로로 쓰지 않는다.

```bash
gh run rerun --job "$OBSERVATION_JOB_ID"
```

현재run tiny artifact를 내려받아 같은 관측기를 읽기 전용으로 재실행할 수도 있다. `STATE_INPUTS`/`STATE_OUTPUT`은 이 실행의 별도 임시 경로이고 `ATTEMPT`는 현재 run attempt다.

```bash
gh run download "$RUN_ID" --pattern 'publish-evidence-*' --dir "$STATE_INPUTS"
GITHUB_REPOSITORY=Kimgyuilli/PeekCart GITHUB_RUN_ID="$RUN_ID" GITHUB_RUN_ATTEMPT="$ATTEMPT" \
  python3 -B scripts/ci-publication-state.py "$EXPECTED_SHA" "$STATE_INPUTS" "$STATE_OUTPUT"
```

기존 `verify-remote`로 config를 대조하고각repo의SHA/latest를확인한다. missing/읽기오류/stale이면성공으로완료하지않는다. 정상6개관측과원전체workflow결과를별도로보존한다. 수동tag쓰기/운영deploy/보호우회/자동retry는이절차에없다.

## 역사 unknown blob의 미해결 범위

run38973369532/head9275556 payment publish는2026-10-16T22:18:34Z SHA push 중 unknown blob/exit1이다. 당시 stdout은 실패한 command substitution 안에 남아 layer/request 상세가 없다. 다른5개 publish job 성공과 그 뒤복구run38981041166의6개성공을 구분한다. PR179는 진단출력 보존을 개선했으며 원서버 원인을 해결한 변경은 아니다.

2026-10-21의 무료 read-only 조회에서는 원9275556 SHA tag가5repo에서200, payment에서404였다. 이는 현재 가용성이고 당시 latest snapshot/원인의 증거는 아니다. 원run image payload는 현재artifact목록에없으며이를복구했다고주장하지않는다. 원자료와해시는 [D-089 evidence](../plans/evidence/D-089/README.md)에 있다.

원인분리를재개하려면재발run의정확시각/서비스/원pushstdout·stderr/검증artifact configID/SHA·manifest와가능한registry응답·요청식별자/서버진단이필요하다. 현재메타데이터관측의성공이나과거build record만으로이없는publish서버자료를대체하지않는다. 추적·감지·복구절차구현과원장애근본원인미해결은부분해결로별도기록한다.
