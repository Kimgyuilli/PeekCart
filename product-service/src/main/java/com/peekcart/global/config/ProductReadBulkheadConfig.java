package com.peekcart.global.config;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.ProductCacheService;
import com.peekcart.product.domain.exception.ProductException;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.Semaphore;

/** Cache hit 바깥이 아닌 miss의 transaction 진입 전에 DB 부하를 제한한다 (ADR-0037). */
@Configuration(proxyBeanMethods = false)
public class ProductReadBulkheadConfig {

    @Bean
    Advisor productReadBulkheadAdvisor(
            @Value("${peekcart.cache.db-max-concurrency:1}") int maximum,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
        if (maximum < 1 || maximum >= poolSize) {
            throw new IllegalArgumentException("cache DB concurrency must be positive and smaller than the connection pool");
        }
        Semaphore permits = new Semaphore(maximum);
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return ProductCacheService.class.isAssignableFrom(targetClass)
                        && Set.of("getProductInfo", "getStock", "getProductList").contains(method.getName());
            }
        };
        MethodInterceptor limit = invocation -> {
            if (!permits.tryAcquire()) throw new ProductException(ErrorCode.PRD_005);
            try {
                return invocation.proceed();
            } finally {
                permits.release();
            }
        };
        var advisor = new DefaultPointcutAdvisor(pointcut, limit);
        advisor.setOrder(-100); // Cache(-200) -> bulkhead -> transaction(LOWEST_PRECEDENCE).
        return advisor;
    }
}
