# Signed Gateway 최초 actual Actions 반례

PR210 head4268af5, 전용 Actions39587253206. 이미지6개는 성공했고 네 리소스 admission은 모두 실패했다. 각 서비스는 실제 User signup/login/JWKS와 Gateway RSA 서명을 거친 ADMIN exactdisabled200 뒤 USER403 기대에서500으로 실패했다. services.log의 GlobalExceptionHandler/AccessDeniedException 경로가 별도 PR211 수정 근거다.

각 결과 complete=false다. 이후 음성 단계와 phase-after DB 불변 검증은 미실행이므로 성공으로 주장하지 않는다. direct plaintext 요청은 초기 source에서 malformed internal token도 동반하는 별도 검사 결함이 있었으며 다음 head에서 이를 제거한다. 이 첫 실행은 해당 요청까지 도달하지 않았다.

각 cleanup은 자기 container6/network1/volume1 제거와 safe artifact scan을 기록했다. bundle은 보존된 sanitized artifact 원문이며 manifest는 파일별 및 archive SHA256을 기록한다. 개인키/JWT/사용자 password는 포함하지 않는다. 실제 운영·replay publish·lastReplayBy 영속 증거가 아니다.

## 수정 후 실제 네 리소스 통과

PR210 head53af54e(PR211 d882fec 의존), Actions39589065938 SUCCESS. 실제 checkout은43c9b8bcf7618f6c8549004a78b3e5a4b277dbac이며 각 이미지와 runner checkout이 일치했다. 네 리소스 각각 baseline11/wrong-key1/restored1/expired2/final1의16 HTTP 사례를 완료했다. 각5개 phase-after의 전체 ledger/outbox가 before와 byte canonical 동등하고 hash가 같다. actual User 짧은TTL 토큰의 만료401과 내부 공개키 오류401 뒤 각각 정상 signed ADMIN closed200 복구를 확인했다.

각 cleanup은 자기 container6/network1/volume1 제거, secret scan은safe=true/removedFiles=[]다. Actions에서 기존 image archive checksum/loadID 대조 및 최종 evidence 검사가 통과했다. 로컬에서는 작은 raw evidence를 내려 case/snapshot/migration/checkout/cleanup을 다시 검사했으며 대형 이미지 archive 자체를 다시 다운로드하지 않았다. no-replay/운영·GKE/lastReplayBy 증거 한계는 그대로다.
