package com.peekcart.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @Scheduled} 구동을 프로퍼티로 게이트한다 (D-032).
 *
 * <p><b>왜 조건부인가</b>: 종전에는 각 서비스 진입점에 {@code @EnableScheduling} 이 무조건
 * 붙어 있어 <b>테스트에서도 백그라운드 잡이 돌았다</b>. 그래서 통합 테스트마다
 * {@code app.outbox.polling.delay=1h} 같은 프로퍼티로 타이머를 개별 무력화하는 관용구가
 * 생겼다. 문제가 둘이다.
 *
 * <ul>
 *   <li><b>opt-out 이라 빠뜨리면 노출된다.</b> 자율적으로 DB 를 고치는 주체가 테스트와 경주한다.
 *       컨테이너를 클래스마다 새로 띄우던 동안에는 각자 자기 DB 를 고쳐서 보이지 않았을 뿐이다
 *       (ADR-0028 로 브로커·DB 를 공유하면서 드러났다).</li>
 *   <li><b>무력화 프로퍼티가 context 캐시를 쪼갠다.</b> {@code @TestPropertySource} 가 곧
 *       캐시 키라, 타이머를 끄려고 프로퍼티를 박는 순간 그 클래스는 자기 context 를 갖는다.</li>
 * </ul>
 *
 * <p><b>기본값은 켜짐</b>({@code matchIfMissing = true})이므로 프로덕션 동작은 변하지 않는다.
 * 테스트는 Gradle {@code test} 태스크의 시스템 프로퍼티로 꺼지고, 타이머 자체를 검증하는
 * 테스트만 {@code @TestPropertySource("app.scheduling.enabled=true")} 로 다시 켠다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
