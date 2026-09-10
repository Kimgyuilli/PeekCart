package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.LedgerOwner;
import com.peekcart.global.kafka.PeekcartService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 이 서비스가 소유한 DLQ 원장의 주인을 선언한다 (계획 ④-c-2b-3b P15-f).
 *
 * <p><b>이 파일은 4서비스 복제본이 아니다</b> — 값이 서비스마다 다르므로 {@code DLQ-PARITY-014}
 * (4벌 byte 동일 검사)의 대상이 아니고, {@code java_files} 목록에도 넣지 않는다.
 *
 * <p>그래서 <b>배선이 lint 밖에 있다</b>. 한 서비스만 잘못 선언되면 그 서비스의 정상 replay 가 전부
 * {@code owner_mismatch} 로 독립 incident 가 되고, 상관이 조용히 0 이 되어 backlog=1 계약이
 * 그 서비스에서만 깨진다. {@code LedgerOwnerWiringTest} 가 주입된 값을 대조한다.
 *
 * <p>정본은 같은 패키지의 {@code DeadLetterConsumer.SELF} 다 — 그 값과 같아야 한다.
 */
@Configuration
public class LedgerOwnerConfig {

    @Bean
    public LedgerOwner ledgerOwner() {
        return new LedgerOwner(PeekcartService.NOTIFICATION);
    }
}
