package com.peekcart.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 분산 락용 {@link RedissonClient} 빈 설정.
 * {@link RedisConnectionDetails}를 통해 연결 정보를 주입받아
 * Testcontainers {@code @ServiceConnection}과도 호환된다.
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisConnectionDetails connectionDetails) {
        RedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + standalone.getHost() + ":" + standalone.getPort());
        return Redisson.create(config);
    }
}
