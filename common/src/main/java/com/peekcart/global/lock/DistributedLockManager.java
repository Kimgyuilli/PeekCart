package com.peekcart.global.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redisson 기반 분산 락 관리자.
 * Redis 장애 시 락 없이 진행하여 {@code @Version} 낙관적 락이 최후 방어선 역할을 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockManager {

    private final RedissonClient redissonClient;

    /**
     * 분산 락 획득을 시도한다.
     *
     * @param key       락 키 (예: {@code inventory-lock:1})
     * @param waitTime  락 대기 시간
     * @param leaseTime 락 보유 시간
     * @param unit      시간 단위
     * @return 락 획득 성공 여부. Redis 장애 시 {@code true} 반환 (fallback)
     */
    public boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit) {
        try {
            RLock lock = redissonClient.getLock(key);
            return lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("분산 락 획득 중 인터럽트 발생: {}", key);
            return false;
        } catch (Exception e) {
            log.warn("Redis 분산 락 획득 실패 (Redis 장애), DB 낙관적 락으로 fallback: {}", key, e);
            return true;
        }
    }

    /**
     * 분산 락을 해제한다. 현재 스레드가 보유한 락만 해제하며, 예외는 무시한다.
     *
     * @param key 락 키
     */
    public void unlock(String key) {
        try {
            RLock lock = redissonClient.getLock(key);
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("분산 락 해제 실패: {}", key, e);
        }
    }
}
