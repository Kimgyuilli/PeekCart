package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.LedgerOwner;
import com.peekcart.global.kafka.PeekcartService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이 서비스의 원장 소유자 <b>배선</b> (계획 ④-c-2b-3b P15-f).
 *
 * <p><b>이 테스트가 없으면 아무도 이 값을 보지 않는다.</b> {@code LedgerOwnerConfig} 는 서비스마다
 * 값이 달라 {@code DLQ-PARITY-014}(4벌 byte 동일 검사) 대상이 아니다. parity lint 를 벗어난다는 말은
 * 곧 오배선이 조용히 통과한다는 뜻이다.
 *
 * <p><b>오배선의 증상은 "실패" 가 아니라 "침묵" 이다</b> — 한 서비스만 틀리면 그 서비스의 정상 replay 가
 * 전부 {@code owner_mismatch} 로 독립 incident 가 되고, "재발행이 N번 실패해도 미결 1건" 계약이
 * 그 서비스에서만 깨진다. 상관이 0 이 되는 것뿐이라 어떤 테스트도 빨개지지 않는다.
 *
 * <p><b>{@code new LedgerOwnerConfig()} 를 직접 부르지 않는다</b> — 그건 리터럴만 확인할 뿐
 * 빈 정의 충돌·조건부 비활성화·중복 빈을 검출하지 못한다. 실제 컨테이너에 올려 확인한다.
 *
 * <p><b>남는 갭</b>: {@code @SpringBootTest} 전체 기동은 MySQL/Redis/Kafka 컨테이너를 요구해
 * 4서비스에 걸면 CI 비용이 크다. 그래서 component scan <b>포함 여부</b>는 어노테이션·패키지 위치로
 * 대신 고정한다 — 스캔 자체가 깨지면 각 서비스의 기존 통합 테스트가 먼저 빨개진다.
 */
@DisplayName("원장 소유자 배선 (order-service)")
class LedgerOwnerWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LedgerOwnerConfig.class);

    @Test
    @DisplayName("컨테이너에 올린 LedgerOwner 는 ORDER 다 — DeadLetterConsumer.SELF 와 같아야 한다")
    void ledgerOwnerIsThisService() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LedgerOwner.class);
            LedgerOwner owner = context.getBean(LedgerOwner.class);
            assertThat(owner.service()).isEqualTo(PeekcartService.ORDER);
            assertThat(owner.prefix()).isEqualTo("order");
            assertThat(owner.owns("order")).isTrue();
            assertThat(owner.owns("someone-else")).isFalse();
            assertThat(owner.owns(null)).isFalse();
        });
    }

    @Test
    @DisplayName("config 는 @Configuration 이고 스캔 대상 패키지에 있다 — 아니면 빈이 아예 안 만들어진다")
    void configIsDiscoverable() {
        assertThat(LedgerOwnerConfig.class.getAnnotation(Configuration.class)).isNotNull();
        assertThat(LedgerOwnerConfig.class.getPackageName()).startsWith("com.peekcart");
    }
}
