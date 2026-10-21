# D-093 초기 평가 준비 검증

2026-10-21 KST, 무료 로컬 Docker의 Prometheus 3.2.1 / Grafana 11.6.0 / fixture 2인스턴스로 검증했다. 정본 rule과 1분 평가 주기, 모든 `for`, baseline120s는 변경하지 않았다. 실제 소스는 clean `b89b4f73ad5228c25d6e348358cec5bd20df6b21`이며 이후 이 PR 변경은 증거/계획/audit 기록뿐이다. 전체 lifecycle과 외부 알림 전달의 새 증거가 아니다.

## 정상 실행

```bash
python3 scripts/monitoring/verify_alerts.py --output .cache/d093-baseline --run --startup-only
```

`baseline/summary.json`: `BASELINE_PASS`, exit 0. 고유 project `peakcart-alert-1792518909`. readiness13표본의 처음과 마지막 간격은 60.81초다. 초기에는 rule health가 ok/inactive여도 vector 또는 개별 instance가 비어 있어 gate를 통과하지 못했다. 예상 모든 vector0/Normal instance/health ok가 생긴 뒤 baseline8표본을 약120초 동안 수집했다. 프로세스 전체 189.31초.

## 제한 시간 음성 대조

```bash
python3 scripts/monitoring/verify_alerts.py --output .cache/d093-timeout --run --startup-only --readiness-timeout 0.001
```

`timeout/run.txt`: 예상 exit 1, `Initial evaluation not ready`와 raw observations 경로. 고유 project `peakcart-alert-1792519110`. 의도적으로 짧은 deadline을 사용했고 첫 실제 raw 표본의 5개 vector/instance가 모두 비어 있었다. baseline과 성공 summary는 없다. 실제 네트워크 장애를 발생시킨 실험은 아니다.

## 증거 읽기

각 실행의 `observations.jsonl.gz`에는 정규화 결과 외에 Grafana rules 원본과 Prometheus query 원본이 있다. manifest는 소스 SHA/dirty, 정본 hash, 실제 version, 컨테이너 및 이미지 identity를 포함한다. `provisioned-rules.json`은 실제 provisioning 응답이다. compressed logs와 startup/cleanup은 실행·정리 원문이다. `artifact-index.json`의 SHA-256으로 파일 무결성을 확인한다.

두 project 모두 자기 컨테이너4개와 network를 제거했다. 검증 도구의 마지막 `compose ps -q`도 비었고, 실행 전후 무관한 기존 momens 컨테이너3개는 유지했다. 사용자 API·GKE·유료 자원·실제 결제·외부 알림은 사용하지 않았다.
