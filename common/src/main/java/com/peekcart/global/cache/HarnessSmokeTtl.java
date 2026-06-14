package com.peekcart.global.cache;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Slf4j
public final class HarnessSmokeTtl {

    private HarnessSmokeTtl() {
    }

    public static Instant resolveExpiry(Duration ttl, Instant now) {
        Objects.requireNonNull(ttl, "ttl must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Instant expiry = now.plus(ttl);
        log.info("resolved expiry={}, ttl={}", expiry, ttl);
        return expiry;
    }
}
