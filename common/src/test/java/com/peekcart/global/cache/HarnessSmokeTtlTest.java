package com.peekcart.global.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HarnessSmokeTtlTest {

    private static final Instant NOW = Instant.parse("2026-04-20T00:00:00Z");

    @Test
    void resolveExpiry_normal() {
        Instant expiry = HarnessSmokeTtl.resolveExpiry(Duration.ofMinutes(5), NOW);
        assertEquals(Instant.parse("2026-04-20T00:05:00Z"), expiry);
    }

    @Test
    void resolveExpiry_zeroTtl_returnsNow() {
        Instant expiry = HarnessSmokeTtl.resolveExpiry(Duration.ZERO, NOW);
        assertEquals(NOW, expiry);
    }

    @Test
    void resolveExpiry_nullTtl_throws() {
        assertThrows(NullPointerException.class,
                () -> HarnessSmokeTtl.resolveExpiry(null, NOW));
    }

    @Test
    void resolveExpiry_nullNow_throws() {
        assertThrows(NullPointerException.class,
                () -> HarnessSmokeTtl.resolveExpiry(Duration.ofMinutes(1), null));
    }
}
