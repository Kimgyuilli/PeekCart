# D-064 Toss 테스트 환경 실호출 (2026-10-26 KST)

> 계획서: `docs/plans/D-064.md` · 도구: `scripts/toss-sandbox/` · 고정한 응답: `payment-service/src/test/resources/toss-sandbox/`
> 스택: 루트 `docker-compose.yml` 을 검증 전용 프로젝트 `peekcart-toss-sandbox` 로 띄우고(새 볼륨) product·order·payment 를 local 프로필로
> 직접 실행했다(`APP_INTERNAL_TOKEN_MODE=DUAL_ACCEPT`). 기준 코드는 브랜치 `feat/D-064-toss-sandbox`(main `a0617b7` 기반).
> 키: Toss 문서에 공개된 결제위젯 테스트 키(`test_gck_docs_`, `test_gsk_docs_`, 상점 `tgen_docs`). 실제 출금이 없는 테스트 환경이다.
> 결제 1건: 주문 2, 10,000원, run `20261026-012535`. 원자료는 저장소 밖 `.cache/toss-sandbox/20261026-012535/` 에 있다.

---

## 결론

- PeekCart 의 승인 경로(`ApprovalExecutor`)와 환불 경로(보상 요청, dispatcher, `RefundExecutor`)가 Toss 테스트 환경의 실제 API 로 끝까지
  성공했다. 승인 원장 SUCCEEDED(1회), 결제 APPROVED, 환불 원장 SUCCEEDED(1회), 결제 REFUNDED, `payment.refunded` 발행.
- 클라이언트의 응답 분류 가정(계획서 §2)은 실제 응답과 모두 맞았다. 분류는 고치지 않았다(P5).
- 실제 응답 8건을 식별값만 바꿔 fixture 로 두고 `TossSandboxResponseTest`(13건)가 클라이언트와 두 실행기를 지나 원장 결론까지 검사한다.
- 같은 멱등키로 다시 보내면 Toss 는 첫 응답(200)을 그대로 준다. PeekCart 는 승인과 환불 모두 주문 단위 고정 멱등키를 쓰므로, 중복 호출은
  `ALREADY_*` 분기가 아니라 성공 분기로 들어가 같은 결론에 닿는다.

## 호출 결과

| 순서 | 호출 | 경로 | 멱등키 | 결과 |
|---|---|---|---|---|
| 1 | 승인 `POST /v1/payments/confirm` | PeekCart | `approve-2` | 200 `DONE`, `approvedAt` `2026-10-26T01:37:13+09:00`. successUrl 도착(01:37:09) 뒤 재고 보호를 거쳐 4초 |
| 2 | 조회 `GET /v1/payments/{paymentKey}` | 직접 | 없음 | 200 `DONE`, `cancels` 없음 |
| 3 | 승인 재요청 | 직접 | `approve-2`(같음) | 200, 1 과 같은 본문 |
| 4 | 승인 재요청 | 직접 | 다른 키 | 400 `ALREADY_PROCESSED_PAYMENT` |
| 5 | 취소 `POST /v1/payments/{paymentKey}/cancel` | PeekCart(`order.compensation.requested` 주입) | `refund-2` | 200 `CANCELED`, `cancels[0].cancelAmount` 10000, `canceledAt` 01:38:25 |
| 6 | 취소 재요청 | 직접 | `refund-2`(같음) | 200, 5 와 같은 본문 |
| 7 | 취소 재요청 | 직접 | 다른 키 | 400 `ALREADY_CANCELED_PAYMENT` |
| 8 | 조회 | 직접 | 없음 | 200 `CANCELED`, 취소 합계 10000 |

환불은 사용자 취소로 일으킬 수 없다(`PAYMENT_COMPLETED` 는 `CANCELLED` 로 전이하지 않는다). 그래서 e2e 시나리오 C 처럼 보상 요청 메시지를
넣었다. 메시지만 인위적이고 PG 취소는 실제 환불 경로가 불렀다.

## 가정·stub 과의 차이

| 항목 | 클라이언트 가정 | PG stub(`stub.py`) | 실제 | 영향 |
|---|---|---|---|---|
| 승인 성공 본문 | `paymentKey`·`orderId`·`totalAmount` 대조, `status` DONE, `approvedAt` 오프셋 형식 | 6개 필드 | 38개 필드. 판정 필드는 가정과 같다 | 없음 |
| 같은 키 재승인 | 별도 가정 없음 | 키를 보지 않고 200 | 200, 첫 응답 재사용 | 없음(성공 분기에서 식별자 대조 뒤 SUCCEEDED) |
| 다른 키 재승인 | 400 `ALREADY_PROCESSED_PAYMENT` 는 조회로 판정 | 키를 보지 않고 200 | 400 `ALREADY_PROCESSED_PAYMENT` | 없음. stub 은 이 응답을 흉내내지 않는다 |
| 같은 키 재취소 | 별도 가정 없음 | script 별 고정 응답 | 200, 첫 응답 재사용 | 없음 |
| 다른 키 재취소 | 400 `ALREADY_CANCELED_PAYMENT` 는 조회로 판정 | already 계열 script 가 400 `ALREADY_CANCELED_PAYMENT` | 같다 | 없음 |
| 조회 | `status`, `cancels[].cancelAmount` 합계 | 두 필드만 | 같은 두 필드를 담은 전체 결제 객체 | 없음 |
| 오류 본문 | `code` 로 분류 | `code`, `message` | `code`, `message`(예: "이미 처리된 결제 입니다.") | 없음 |

stub 은 고치지 않았다. 다른 키 재승인을 흉내내지 않는 점은 e2e 가 그 분기를 지나지 않는다는 뜻이고, 그 분기는 이번 fixture 테스트가 실제
본문으로 덮는다.

## 읽는 법

### ① `ALREADY_*` 분기는 키가 다를 때만 탄다

같은 키 재요청은 첫 응답을 재사용하므로, 주문 단위 고정 멱등키를 쓰는 PeekCart 에서 `ALREADY_PROCESSED_PAYMENT` 와
`ALREADY_CANCELED_PAYMENT` 는 다른 키로 같은 결제를 부른 경우에만 나온다. 두 분기는 방어용이고, 실제 본문으로 조회를 거쳐 SUCCEEDED 에
닿는 것은 fixture 테스트로 확인했다.

### ② 응답 본문 해석은 JVM 기본 문자셋에 기댄다

`TossPaymentClient.readBody` 는 응답 바이트를 문자셋 지정 없이 문자열로 만든다. 이번 로컬 실행과 테스트 JVM 은 UTF-8 이라 `method`
("간편결제")가 바르게 저장됐다(HEX `EAB084ED8EB8EAB2B0ECA09C`). 운영 이미지(`eclipse-temurin:17-jre`)의 기본 문자셋은 확인하지 않았다.
분류에는 영향이 없어 이번 범위에서 고치지 않았다(후속 후보: UTF-8 명시).

## 실행 중 발견

| 발견 | 처분 |
|---|---|
| 처음 넣은 API 개별 연동 클라이언트 키(`test_ck_`)로 구버전 결제창을 열자 결제창 열기 요청(`px-payment-parameters`)이 401 이었다 | 결제위젯 키와 위젯 SDK 로 바꿨다(계획서 §1 정정) |
| 결제창에서 카카오페이를 고르자 실제 카카오페이 앱으로 이어져 사용자 지갑에 실제 충전이 일어났다. successUrl 에 닿지 않아 승인 API 는 부르지 않았고 결제는 성립하지 않았다 | 절차를 신용·체크카드만 쓰는 것으로 고쳤다(계획서 §4). 충전 잔액은 사용자 소유다 |
| 성공한 결제의 결제수단은 간편결제(토스페이)였다 | 카드사 결제창 경로는 이번에 지나지 않았다(아래 못 한 것) |
| 카드사 인증 창이 팝업이라 브라우저 자동화 탭에서는 `USER_CANCEL` 로 끝났다 | 사용자가 직접 결제창을 통과했다 |
| 기존 로컬 볼륨(`peakcart_mysql-data`)은 서비스별 DB 계정이 생기기 전에 초기화돼 세 서비스가 `Access denied` 로 뜨지 못했다 | 검증 전용 compose 프로젝트(새 볼륨)를 썼다. 기존 볼륨은 건드리지 않았다 |
| 도구가 승인 원장의 `WAITING_STOCK`(재고 보호 대기)을 종결로 오판했다 | `ApprovalStatus` 전이 기준으로 진행 중 상태를 `WAITING_STOCK`, `READY`, `CLAIMED` 로 고쳤다 |
| 컨테이너 기본 mysql 클라이언트 문자셋으로 읽으면 저장된 한글이 `?` 로 보였다(저장값은 정상) | 도구 질의에 `--default-character-set=utf8mb4` 를 넣었다 |
| 문서 공개 키로 만든 결제는 코어 API 로 조회되지 않는다는 커뮤니티 보고가 있었다 | 이번 결제는 조회 두 번 모두 200 이었다 |

## 확인하지 못한 것

- 카드사 결제창을 거친 카드 결제(`method` "카드"). 서버의 승인·취소·조회 API 는 같지만 본문의 `card` 객체 구성은 이번 결제가 토스페이였다.
- 웹훅 외부 수신과 운영 수준 checkout 통합(계획서 §7, 범위 밖).
- 409 `IDEMPOTENT_REQUEST_PROCESSING` 의 실제 재현(첫 요청이 처리 중이어야 해서 결정적으로 만들 수 없다). D-059 분류는 문서 기준 그대로다.
- 운영 이미지의 JVM 기본 문자셋(읽는 법 ②).

## 정리

- 스택: 서비스 3개를 내리고 검증 전용 compose 프로젝트를 볼륨째 지웠다. 기존 개발 볼륨 3개는 그대로다.
- 원자료 `.cache/toss-sandbox/` 는 git 이 무시한다. 응답 원문에 카드 번호 일부와 결제별 secret 이 있어 fixture 에는 정리본만 넣었다.
  PR 뒤 지워도 된다. 키 파일 `.cache/toss-sandbox.env` 도 같다.
