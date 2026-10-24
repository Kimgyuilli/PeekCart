package com.peekcart.global.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 기동이 끝난 뒤 선언 토픽을 브로커에 <b>한 번 더</b> 적용한다 (ADR-0020 §D4-1, D-106).
 *
 * <h2>왜 한 번 더인가</h2>
 * {@link KafkaAdmin} 은 기동 중 describe 에 실패한 토픽을 생성 대상으로 잡는다. 그 사이 다른 서비스의
 * 컨슈머가 같은 토픽을 브로커 자동 생성으로 먼저 만들면 생성이 {@code TopicExistsException} 으로 실패한다.
 * {@code KafkaAdmin} 은 이 예외를 DEBUG 로그만 남기고 삼키고, 그 토픽을 config 갱신 대상에서도 뺀다
 * (spring-kafka 3.3.14 {@code KafkaAdmin.addOrModifyTopicsIfNeeded}). 그 토픽은 브로커 기본 config 와
 * 자동 생성 파티션 수로 남는다. GKE 세션(2026-10-24)의 payment 토픽 6개가 이 상태였다.
 *
 * <p>기동이 끝났을 때 그 토픽은 이미 있으므로, 다시 적용하면 기존 토픽 경로(파티션 증설, 다른 config 갱신)를 탄다.
 *
 * <h2>창을 줄일 뿐이다</h2>
 * 기동 중 적용이 통째로 실패했으면(브로커 미가용) 이번 적용이 첫 생성을 맡고 같은 경합을 다시 겪을 수 있다.
 *
 * <h2>파티션 증설의 대가</h2>
 * 경합 토픽은 자동 생성 파티션 수(브로커 기본 1)로 있다가 여기서 선언 수로 늘어난다. 이미 구독 중인 다른 서비스의
 * 컨슈머는 metadata 갱신({@code metadata.max.age.ms} 기본 5분)까지 새 파티션을 소비하지 않는다. 소유 서비스보다
 * 먼저 자동 생성된 토픽이 기동 중 적용에서 겪는 것과 같은 성질이고, 빈 브로커 첫 기동 직후에만 생긴다.
 * 생성 경로를 하나로 만드는 처방은 D-107 이다.
 *
 * <h2>실패해도 기동을 깨지 않는다</h2>
 * 컨텍스트 초기화 밖에서 부른 {@link KafkaAdmin#initialize()} 는 실패를 예외로 던진다. 그대로 두면
 * {@link ApplicationReadyEvent} 리스너 예외로 애플리케이션이 종료된다. 기동 중 적용은 같은 실패를 로그만 남기고
 * 넘어가므로({@code fatalIfBrokerNotAvailable} 기본 false) 여기서도 ERROR 로그로 맞춘다.
 *
 * <h2>빈으로 자동 등록하지 않는다</h2>
 * {@code @Component} 로 두면 {@code KafkaAdmin} 이 없는 user-service 의 컨텍스트가 깨진다.
 * 토픽을 선언하는 서비스의 Kafka 설정이 각자 {@code @Bean} 으로 등록한다.
 */
@Slf4j
@RequiredArgsConstructor
public class KafkaTopicReconciler implements ApplicationListener<ApplicationReadyEvent> {

    private final KafkaAdmin kafkaAdmin;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            kafkaAdmin.initialize();
        } catch (RuntimeException e) {
            log.error("기동 뒤 토픽 재적용 실패. 생성 경합을 겪은 토픽이 선언 config 없이 남았을 수 있다", e);
        }
    }
}
