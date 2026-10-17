package com.peekcart.global.config;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductReadBulkheadConfigTest {
    @Test
    void invalidLimitCannotConsumeTheWholePool() {
        var config = new ProductReadBulkheadConfig();
        for (int limit : new int[]{-1, 0, 10, 11}) {
            assertThatThrownBy(() -> config.productReadBulkheadAdvisor(limit, 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void failureAlwaysReturnsPermit() throws Throwable {
        var advisor = new ProductReadBulkheadConfig().productReadBulkheadAdvisor(1, 10);
        var interceptor = (MethodInterceptor) advisor.getAdvice();
        var invocation = mock(MethodInvocation.class);
        when(invocation.proceed()).thenThrow(new IllegalStateException("DB failure")).thenReturn("recovered");
        assertThatThrownBy(() -> interceptor.invoke(invocation)).hasMessage("DB failure");
        assertThat(interceptor.invoke(invocation)).isEqualTo("recovered");
    }
}
