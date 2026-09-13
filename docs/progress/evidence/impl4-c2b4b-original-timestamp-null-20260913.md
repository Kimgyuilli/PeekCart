# `original_timestamp` NULL 비율 증적 — 2026-09-13 (④-c-2b-4b P22)

- 대상: 4개 서비스 원장 `dead_letter_records` (peekcart_order · peekcart_product · peekcart_payment · peekcart_notification)
- 목적: ADR-0020 §D5-2 가 **미측정**이라고 남긴 가용성 손실 — `original_timestamp` 가 NULL 인 행은
  `replay_deadline`(= `original_timestamp + dlq-replay-window`)을 계산할 수 없어 **replay 불가**다.
- 처분 기준: **ADR-0022 §D6 의 분기표**(본문에 선기록 — 측정 후 ADR 본문을 고치는 것은 immutable 규약 위반)

## 결과 — **미측정 (표본 0)**

| 서비스 | 전체 미결 | `RESOLVED_ORIGIN` | replay 후보 | NULL | 비율 |
|---|---|---|---|---|---|
| order | — | — | — | — | **미측정** |
| product | — | — | — | — | **미측정** |
| payment | — | — | — | — | **미측정** |
| notification | — | — | — | — | **미측정** |

**사유**: 이 측정이 요구하는 것은 **실제 운영 원장의 기존 행 분포**다(ADR §D5-2). 현재 이 프로젝트에는
상시 가동 중인 운영 클러스터가 없고, 원장에 행을 쌓은 환경도 없다.

**대체하지 않는다**:
- **fixture 를 세지 않는다** — 테스트가 심은 행을 세는 것은 자기대조이며 가용성 손실을 측정하지 못한다.
  ADR §D5-2 가 미측정이라고 한 것이 정확히 그 이유다.
- **0건을 "NULL 0%" 로 적지 않는다** — 표본이 없는 것과 NULL 이 없는 것은 다른 사실이다.
  0% 로 적으면 "손실 없음" 이라는 결론이 근거 없이 생긴다.

## 재측정 방법 (운영 표본 확보 후)

서비스별 스키마에 **read-only** 로 실행한다. 4개 스키마 각각에서 따로 돌린다(DB-per-service).

```sql
SELECT
    COUNT(*)                                                             AS total_unresolved,
    SUM(origin_kind = 'RESOLVED_ORIGIN')                                 AS resolved_origin,
    SUM(origin_kind = 'RESOLVED_ORIGIN' AND replay_deadline IS NULL
        AND original_timestamp IS NULL)                                  AS replay_blocked_by_null_ts,
    SUM(original_timestamp IS NULL)                                      AS null_original_timestamp,
    NOW(6)                                                               AS measured_at
FROM dead_letter_records
WHERE status IN ('OPEN', 'ACKED');
```

```bash
for svc in order product payment notification; do
  kubectl -n peekcart exec deploy/mysql -- mysql -N -B \
    -u"peekcart_${svc}" -p"<secret>" -D "peekcart_${svc}" -e "<위 쿼리>"
done
```

## 처분 (ADR-0022 §D6 분기표)

현재 결과는 **표본 0** 이므로 분기표의 첫 행이 적용된다 — **"미측정" 으로 기록하고 운영 표본 확보 후
재평가한다. 그 재평가는 새 ADR 사유가 아니라 이 증적 문서의 갱신이다.**

따라서 이 시점에 **가용성 손실 수용 기준을 확정하지 않는다**. 기준 자체는 ADR-0022 §D6 이 미리 정해뒀으므로,
측정값만 채우면 처분이 자동으로 결정된다.
