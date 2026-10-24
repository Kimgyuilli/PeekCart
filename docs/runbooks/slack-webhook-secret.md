# Runbook — Slack webhook Secret 소유와 교체

> 대상: 사용자 알림·운영 경보 Slack incoming webhook URL 두 개와, 그 값을 담는 Secret Manager·Kubernetes Secret
> 전제: 사용자/운영 webhook 분리(`docs/plans/done/D-092-slack-split.md`), Grafana 운영 라우팅(`k8s/monitoring/shared/grafana-notification-routing.yml`)
> 값은 어디에도 내지 않는다. 화면, 셸 히스토리, 프로세스 인자, 로그, 저장소, incident 기록 모두 같다.

## 0. 요약

| 상황 | 할 일 |
|---|---|
| 세션에서 스택을 처음 띄운다 | §2 주입, §3 확인 |
| 정기 교체 | §4 를 순서대로. 이전 webhook 은 canary 통과 뒤에 지운다 |
| URL 이 노출됐다 | §5. Slack 에서 그 webhook 부터 지운다 |
| notification-service 가 시작하지 않는다 | Slack 키가 없거나 두 값이 같다. §3 확인 뒤 §2 를 다시 한다 |

## 1. 값과 소유

| 값 | 정본 | Kubernetes 주입 | 소비자 | 반영 조건 |
|---|---|---|---|---|
| 사용자 알림 webhook | Secret Manager `peekcart-slack-user-webhook` | `peekcart/notification-service-secret` 의 `SLACK_USER_WEBHOOK_URL` | notification-service 사용자 알림(`notification.slack.user-webhook-url`) | notification-service 재시작 |
| 운영 경보 webhook | Secret Manager `peekcart-slack-ops-webhook` | `peekcart/notification-service-secret` 의 `SLACK_OPS_WEBHOOK_URL`, `monitoring/grafana-ops-webhook` 의 `url` | notification-service `SlackPort` 운영 호출(Outbox·DLQ, `slack.webhook.url`), Grafana contact point `peekcart-operations`(env `PEAKCART_OPS_SLACK_WEBHOOK_URL`) | notification-service 와 Grafana 재시작 |

- Slack 앱 `PeekCart Alerts`(`test` 워크스페이스, 채널 2개), Secret Manager 두 시크릿, Kubernetes Secret 의 Slack 키 셋은 운영자가 소유한다. 현재 값을 만들고 넣은 사람은 저장소 소유자다(D-078 S0).
- 정본은 Secret Manager 다. Kubernetes Secret 은 세션마다 정본에서 다시 만든다. 현재 프로젝트는 `peekcart-d078` 이고, D-078 세션 뒤 결제 연결이 끊겨 있다.
- 저장소에는 값도 placeholder 도 두지 않는다. `secret.yml` 은 Slack 키를 선언하지 않는다. base·local 기본값은 서로 다른 loopback endpoint(`127.0.0.1:9`)라 밖으로 나가지 않는다.
- 두 webhook 은 서로 다른 채널이어야 한다. notification-service 는 키가 없거나 두 URL 이 같으면 시작하지 않지만, 서로 다른 webhook 이 같은 채널을 가리키는지는 알 수 없다. 채널 분리는 §3 canary 로만 확인된다.

## 2. 주입

아래 명령은 값을 파일 디스크립터로만 넘긴다. `--from-literal`, `-p '<json>'`, `echo` 는 값을 프로세스 인자나 화면에 남기므로 쓰지 않는다.

```bash
P=peekcart-d078   # Secret Manager 프로젝트
```

1. Grafana 보다 먼저 `monitoring` namespace 에 운영 webhook Secret 을 만든다. 이 Secret 은 운영자만 만들고 kustomize·helm 이 관리하지 않으므로 통째로 apply 한다. Grafana 가 먼저 떴으면 env 가 비어 있으므로 4 로 재시작한다.

   ```bash
   kubectl create secret generic grafana-ops-webhook -n monitoring \
     --from-file=url=<(printf '%s' "$(gcloud secrets versions access latest --secret=peekcart-slack-ops-webhook --project "${P}")") \
     --dry-run=client -o yaml | kubectl apply -f -
   ```

2. 배포 래퍼(`scripts/deploy-overlay.sh`)를 먼저 돌려 `notification-service-secret` 을 만든다. 이 Secret 의 DB 키는 래퍼의 `kubectl apply -k` 가 관리한다. Slack 키 둘은 `patch` 로만 더한다. `kubectl apply` 로 넣으면 마지막 적용 기록이 바뀌어 다음 배포가 Slack 키를 지울 수 있다. 첫 배포 직후 notification-service 는 Slack 키가 없어 시작하지 못하므로 patch 뒤 4 로 재시작한다.

   ```bash
   kubectl -n peekcart patch secret notification-service-secret --type merge --patch-file <(
     jq -n \
       --rawfile u <(gcloud secrets versions access latest --secret=peekcart-slack-user-webhook --project "${P}") \
       --rawfile o <(gcloud secrets versions access latest --secret=peekcart-slack-ops-webhook --project "${P}") \
       '{stringData: {SLACK_USER_WEBHOOK_URL: ($u | rtrimstr("\n")), SLACK_OPS_WEBHOOK_URL: ($o | rtrimstr("\n"))}}')
   ```

3. 배포를 다시 하거나 namespace 를 지웠다 다시 만들었으면 §3 의 키 확인부터 다시 한다. namespace 를 지우면 Slack 키도 사라진다.

4. 두 소비자 모두 시작할 때만 값을 읽는다. 값을 바꾼 뒤에는 재시작한다.

   ```bash
   kubectl -n peekcart rollout restart deployment/notification-service
   kubectl -n peekcart rollout status deployment/notification-service --timeout=5m
   # GKE install(k8s/monitoring/gke/install.sh) 기준 deployment.apps/kube-prometheus-stack-grafana
   GRAFANA=$(kubectl -n monitoring get deploy -l app.kubernetes.io/name=grafana -o name)
   kubectl -n monitoring rollout restart "${GRAFANA}"
   kubectl -n monitoring rollout status "${GRAFANA}" --timeout=5m
   ```

## 3. 확인

값을 내지 않는 확인만 한다. `kubectl get secret -o yaml`, `kubectl describe` 로 값을 보지 않는다. base64 도 값이다.

```bash
# 키 이름
kubectl -n peekcart get secret notification-service-secret -o json | jq -r '.data | keys[]'   # SLACK_OPS_WEBHOOK_URL, SLACK_USER_WEBHOOK_URL 포함
kubectl -n monitoring get secret grafana-ops-webhook -o json | jq -r '.data | keys[]'          # url

# 정본 형식. 각각 1
for s in peekcart-slack-user-webhook peekcart-slack-ops-webhook; do
  gcloud secrets versions access latest --secret="${s}" --project "${P}" | grep -c '^https://hooks.slack.com/services/'
done

# 같은 값인지는 해시 앞부분으로만 본다
h() { tr -d '\n' | shasum -a 256 | cut -c1-16; }
gcloud secrets versions access latest --secret=peekcart-slack-ops-webhook --project "${P}" | h
kubectl -n peekcart get secret notification-service-secret -o json | jq -r '.data.SLACK_OPS_WEBHOOK_URL' | base64 -d | h
kubectl -n monitoring get secret grafana-ops-webhook -o json | jq -r '.data.url' | base64 -d | h
gcloud secrets versions access latest --secret=peekcart-slack-user-webhook --project "${P}" | h
kubectl -n peekcart get secret notification-service-secret -o json | jq -r '.data.SLACK_USER_WEBHOOK_URL' | base64 -d | h
```

운영 값 세 줄이 같고, 사용자 값 두 줄이 같고, 운영과 사용자가 서로 달라야 한다.

canary 는 D-078 S4.7 과 같다. Grafana contact point 테스트로 운영 채널에 1건, gateway 로 주문 1건을 넣어 사용자 채널에 1건을 보낸다. 두 채널에 한 건씩, 서로 다른 채널로 왔는지 운영자가 Slack 에서 본다. notification-service 의 운영 경로(Outbox·DLQ 알림)는 canary 대상이 아니다. 그 경로가 같은 정본을 쓰는지는 위 해시 대조로만 본다.

## 4. 정기 교체

이전 webhook 과 이전 Secret Manager 버전은 canary 가 통과할 때까지 살려 둔다. 롤백은 그 둘로 한다.

```bash
S=peekcart-slack-ops-webhook   # 또는 peekcart-slack-user-webhook
```

1. Slack 앱 `PeekCart Alerts` 의 Incoming Webhooks 설정에서 같은 채널로 새 webhook 을 발급한다.
2. 새 값을 정본에 새 버전으로 넣는다. 붙여넣는 값이 화면에 나오지 않게 읽는다.

   ```bash
   read -rs NEW_URL   # 붙여넣고 Enter
   printf '%s' "${NEW_URL}" | gcloud secrets versions add "${S}" --project "${P}" --data-file=-
   unset NEW_URL
   ```

3. §3 의 정본 형식 검사와 운영·사용자 해시 비교를 다시 한다.
4. 클러스터가 떠 있으면 §2 의 1·2 로 다시 주입하고 §2 의 4 로 재시작한다. 떠 있지 않으면 다음 세션이 `latest` 를 읽는다.
5. §3 canary 가 통과하면 Slack 에서 이전 webhook 을 지우고 이전 버전을 끈다. 끈 버전은 다음 교체 때 `destroy` 한다.

   ```bash
   gcloud secrets versions list "${S}" --project "${P}"
   gcloud secrets versions disable <이전-버전> --secret="${S}" --project "${P}"
   ```

롤백: canary 가 실패하면 §2 명령의 `latest` 를 `<이전-버전>` 으로 바꿔 다시 주입하고 재시작한 뒤, 새 버전을 `disable` 한다. 이전 webhook 을 아직 지우지 않았으므로 전달이 돌아온다.

incident·progress 에는 바꾼 시크릿 이름, 새 버전 번호, 이전 webhook 을 지운 시각, canary 결과만 적는다.

## 5. 유출 대응

URL 을 아는 사람은 누구나 그 채널에 글을 쓸 수 있다. 저장소·로그·화면 공유·채팅에 노출됐으면 §4 의 순서를 바꾼다.

1. Slack 에서 노출된 webhook 을 바로 지운다. 그 채널 전달은 2 가 끝날 때까지 실패한다. canary 를 기다리지 않는다.
2. §4 의 1~4 로 새 webhook 을 넣는다. 노출된 값의 Secret Manager 버전은 `disable` 하지 않고 `destroy` 한다.
3. 값이 저장소에 들어갔으면 1 에서 이미 무효가 됐다. 이력 재작성 여부는 운영자가 정하고 경위를 incident 에 남긴다.
