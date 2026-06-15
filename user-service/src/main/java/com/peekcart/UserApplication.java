package com.peekcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;

/**
 * User 서비스 진입점 (ADR-0011 멀티모듈 · PR2b · ADR-0014 발급 owner).
 * <p>base 패키지를 {@code com.peekcart} 로 두어 컴포넌트/엔티티/JPA 리포지토리 스캔이
 * 공유 {@code com.peekcart.global.*}(common·common-auth·observability + User 전속 발급 issuer)와
 * {@code com.peekcart.user.*}(도메인)을 모두 포함하도록 한다.
 *
 * <p><b>Kafka 비로드 (U1, GP-2 P1 #4/2차 #2)</b>: User 는 이벤트 발행/소비를 하지 않는다.
 * {@code spring-kafka} 는 :common 의 {@code api} 의존으로 classpath 에 전이되지만, 커스텀
 * {@code KafkaConfig}(ProducerFactory/error handler 빈)는 root 전속이라 user-service 에 없다.
 * 남는 것은 Boot 의 {@link KafkaAutoConfiguration}(미사용 KafkaTemplate)뿐이므로 이를 명시 제외해
 * Kafka 인프라 빈을 0개로 둔다(더미 bootstrap lazy 은폐 금지 — 부채 은폐 회피).
 */
@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }

}
