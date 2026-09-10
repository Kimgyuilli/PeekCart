package com.peekcart.global.kafka;

import java.util.Objects;

/**
 * 이 프로세스가 소유한 DLQ 원장의 주인 (계획 ④-c-2b-3b P15-f, ADR-0021 §D1 축 2).
 *
 * <p><b>왜 빈 주입인가.</b> {@code DeadLetterRecorder} 는 4서비스가 <b>byte 동일한 복제본</b>이라
 * {@link PeekcartService} 를 하드코딩할 수 없다. 그렇다고 {@code record(DlqOrigin, PeekcartService)} 로
 * 시그니처를 넓히면 <b>호출자가 매번 소유자를 고를 수 있어</b> 내부 API 의 신뢰 경계가 약해지고,
 * 실측 44곳(main+test)의 호출부를 전부 고쳐야 한다. 그래서 값을 <b>주입</b>받는다 —
 * {@code record(DlqOrigin)} 시그니처는 불변이다.
 *
 * <p><b>{@code DeadLetterProperties} 에 두지 않는 이유</b>: 코드에 이미 있는 사실(각 서비스의
 * {@code DeadLetterConsumer.SELF})을 설정에 복제하면 둘이 어긋날 때 <b>설정이 이겨서 남의 사건에
 * 자식을 붙인다</b>. 소유권은 배포 설정이 아니라 코드 정체성이다.
 *
 * <p>서비스별 {@code LedgerOwnerConfig} 가 빈으로 제공한다. 그 config 는 서비스마다 값이 달라
 * {@code DLQ-PARITY-014}(4벌 byte 동일 검사)에 걸리지 않으므로, <b>배선 자체는 서비스별 context
 * 테스트가 대조한다</b>(P15-f 주석).
 *
 * @param service 이 프로세스가 원장을 소유하는 서비스
 */
public record LedgerOwner(PeekcartService service) {

    public LedgerOwner {
        Objects.requireNonNull(service, "ledger owner service 는 null 일 수 없습니다");
    }

    /**
     * replay 헤더 {@code pc-replay-ledger-owner} 와 대조할 값.
     *
     * <p>헤더에는 {@link PeekcartService#prefix()} 가 실린다(④-c-2b-3a P14-c).
     */
    public String prefix() {
        return service.prefix();
    }

    /** 헤더에서 읽은 owner 문자열이 이 프로세스를 가리키는가. {@code null} 은 불일치다. */
    public boolean owns(String headerOwner) {
        return prefix().equals(headerOwner);
    }
}
