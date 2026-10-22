# D-078 latency metrics audit

상태: 의도적 생략 (계획 리뷰·diff 리뷰 미실시, 별도 Codex 리뷰 미호출)

## 구현

- 브랜치 `chore/d078-latency-metrics`, 기준 `4785396`.
- 사용자 요청으로 후보 지표를 기존 증적과 먼저 대조했다. gateway 오버헤드는 이미 있어 뺐고, 사가 지연 분포가 Phase 4 계획 P7 의 미산출 공백임을 확인했다.
- 처음에는 사가 지연을 order·product 두 DB 를 합쳐 구하려 했다. `orders.reservation_confirmed_at` 이 있어 order DB 하나로 충분하다.

## 검증 (2026-10-23, 로컬)

- `test_d078_latency.py` 5건, `test_d078_capture.py` 10건 OK.
- Flyway 첫 적용이 order V5·product V4 에서 collation 혼합 오류로 실패했다. Flyway 이미지의 JDBC 연결 collation 이 앱과 달라서다. 마이그레이션은 고치지 않고 `-initSql=SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci` 로 맞춰 order v14·product v13 을 적용했다.
- 손 계산 대조: 왕복 1.5·2.0·7.25s + NULL 1 → p50 2000ms, p95 7250ms, 제외 1(CANCELLED). outbox order 200·600ms + PENDING 1 제외, product 5100ms. `mysql -B` 의 NULL 표기 `NULL` 처리 확인. 컨테이너·네트워크 제거.
