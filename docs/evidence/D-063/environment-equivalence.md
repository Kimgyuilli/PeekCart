# D-063: independent environment-equivalence assessment

2026-10-17, read-only assessment against main 92755566074fc28d78e53dd84b2b085a7fbfdb38. No execution or approval of unrun tests.

## Verdict

A free local real Prometheus + Grafana stack can satisfy D-063's alert-rule evaluation gap, conditional on actual evidence. ADR-0015 Consequences names real Prometheus/Grafana, Grafana __expr__ semantics, input series and firing thresholds; it does not require a GKE-specific evaluator. PHASE4:1056 explicitly identifies startup plus fixtures. PHASE5:244's earlier '(GKE session required)' is the then-available execution environment, not an ADR invariant requiring paid cloud execution. The shared rule file explicitly scopes its contract to evaluation and UI alert state, excluding external delivery.

## Required real evidence

- Extract/provision the repository's `k8s/monitoring/shared/grafana-alerts.yml` alerts.yaml without changing rule expressions, datasource UID `prometheus`, rule identities, grouping, evaluation interval, or `for` durations. Record source SHA and hashes.
- Start actual Prometheus and Grafana with pinned versions/digests and fixture exporter; show real scrape/query series with expected labels.
- Observe rule/application-specific Normal -> Pending -> Alerting -> Normal through evaluator/API evidence, timestamps and query values. Verify `application`, status and `signal` isolation.
- Preserve compensation backlog 15m, DLQ backlog 10m, Outbox failed 5m and subsequent D-070 fallback-get 10m / evict immediate contracts. Accelerated for values are not evidence for the production timing contract.
- Resolve by returning the series to a healthy value, not by removing it. NoData is not successful resolution.
- Include wrong application/status and below-threshold negative controls, non-sustained breach that returns to Normal before for expires, and multiple service instances to reveal label collisions.
- Capture container versions/digests, start/stop times, source checkout, rule provisioning response, queries and alert state transitions. Existing installation scripts use an unpinned Helm chart, so do not claim binary parity with a deployed GKE version.

## Explicitly unverified by this harness

GKE ServiceMonitor discovery, namespace/selector routing, ConfigMap sidecar delivery, internal load balancer, production metric emission/scrape connectivity, real incident occurrence, and Slack/PagerDuty delivery. These remain separate deployment/operational checks, not implied by local fixture evaluation. Preserve prior history and append this execution boundary; do not rewrite historical unverified deployments as completed.

The actual local stack is a heavy execution and must hold root's single global slot. This assessment does not itself complete D-063.
