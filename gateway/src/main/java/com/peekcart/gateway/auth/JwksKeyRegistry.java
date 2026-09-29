package com.peekcart.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User JWKS(`/.well-known/jwks.json`)를 공개키 <b>정본</b>으로 삼는 kid→RSA 공개키 레지스트리
 * (ADR-0013 D1).
 *
 * <p><b>snapshot 교체 계약</b>: 성공적으로 받은 <b>비어 있지 않은</b> JWKS 는 기존 캐시에
 * merge 하지 않고 <b>통째로 교체</b>한다. merge(put-only)면 User 가 침해/폐기한 kid 가 Gateway 에
 * 영구 잔존해, 그 키로 새로 서명된 토큰을 재시작 전까지 계속 수용하게 된다.
 * 조회 실패·빈 응답일 때만 직전 snapshot 을 유지한다(LKG).
 *
 * <p><b>LKG / 실패 분류</b>:
 * <ul>
 *   <li>snapshot 에 kid 존재 → JWKS 가 죽어 있어도 <b>정상 처리</b>. 갱신 실패는 경보만.</li>
 *   <li>unknown kid → 즉시 refresh. 성공했는데도 부재면 {@link UnknownKidException}(→<b>401</b>),
 *       refresh 자체가 실패하면 {@link JwksUnavailableException}(→<b>503</b>).</li>
 *   <li>cold start(usable key 0) → {@link #hasUsableKey()} false → readiness 미충족.</li>
 * </ul>
 */
@Component
public class JwksKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(JwksKeyRegistry.class);

    private final WebClient webClient;
    private final JwtGatewayProperties properties;

    /** 불변 snapshot. 성공 fetch 시 통째로 교체되고, 실패 시 유지된다(LKG). */
    private final AtomicReference<Map<String, RSAPublicKey>> snapshot = new AtomicReference<>(Map.of());
    private final AtomicReference<Instant> lastRefreshAttempt = new AtomicReference<>(Instant.EPOCH);
    /** 실행 <b>중</b>인 fetch 에만 유지한다(완료 시 해제) — 완료된 Mono 재사용으로 refresh 를 건너뛰지 않도록. */
    private final AtomicReference<Mono<Void>> inFlight = new AtomicReference<>();

    public JwksKeyRegistry(WebClient.Builder webClientBuilder, JwtGatewayProperties properties) {
        this.properties = properties;
        this.webClient = webClientBuilder.build();
    }

    /** usable key 가 1개 이상인지 — readiness 판정용(cold start 시 트래픽 미수신). */
    public boolean hasUsableKey() {
        return !snapshot.get().isEmpty();
    }

    /** 현재 snapshot 이 보유한 kid 집합(테스트/진단용). */
    public java.util.Set<String> knownKids() {
        return snapshot.get().keySet();
    }

    /**
     * kid 로 공개키를 해석한다. snapshot 우선(LKG), miss 면 refresh 후 재조회.
     *
     * @throws UnknownKidException      refresh 후에도 kid 부재 (→ 401)
     * @throws JwksUnavailableException refresh 실패 (→ 503)
     */
    public Mono<RSAPublicKey> resolve(String kid) {
        if (kid == null || kid.isBlank()) {
            return Mono.error(new UnknownKidException("kid 부재"));
        }
        RSAPublicKey cached = snapshot.get().get(kid);
        if (cached != null) {
            return Mono.just(cached);
        }
        return refresh().then(Mono.defer(() -> {
            RSAPublicKey refreshed = snapshot.get().get(kid);
            if (refreshed != null) {
                return Mono.just(refreshed);
            }
            // refresh 는 성공했는데 kid 가 없다 → 위조/폐기 키 → 401
            return Mono.error(new UnknownKidException("알 수 없는 kid: " + kid));
        }));
    }

    /** 주기 갱신 진입점 — 실패해도 snapshot 을 보존하고 경보만 남긴다(LKG). */
    public Mono<Void> refreshQuietly() {
        return refresh().onErrorResume(e -> {
            log.warn("[alert] JWKS 갱신 실패 — last-known-good {}개 키로 계속 서비스합니다",
                    snapshot.get().size(), e);
            return Mono.empty();
        });
    }

    private Mono<Void> refresh() {
        Instant now = Instant.now();
        Instant last = lastRefreshAttempt.get();
        if (Duration.between(last, now).compareTo(properties.jwksRefreshCooldown()) < 0
                || !lastRefreshAttempt.compareAndSet(last, now)) {
            // cooldown 중이면 *실행 중인* fetch 에만 합류한다. 완료된 fetch 는 inFlight 에서 해제돼 있어
            // 재사용되지 않는다(c3:6) — 합류 대상이 없으면 그냥 통과해 caller 가 snapshot 을 재조회한다.
            Mono<Void> running = inFlight.get();
            return running != null ? running : Mono.empty();
        }
        Mono<Void> fetch = fetchJwks()
                .doOnNext(this::applySnapshot)
                .then()
                .doFinally(signal -> inFlight.set(null))
                .cache();
        inFlight.set(fetch);
        return fetch;
    }

    private Mono<Map<String, Object>> fetchJwks() {
        return webClient.get()
                .uri(properties.jwksUri())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(properties.jwksTimeout())
                .onErrorMap(e -> !(e instanceof JwksUnavailableException),
                        e -> new JwksUnavailableException("JWKS 조회 실패: " + properties.jwksUri(), e));
    }

    @SuppressWarnings("unchecked")
    private void applySnapshot(Map<String, Object> jwks) {
        Object keysNode = jwks.get("keys");
        if (!(keysNode instanceof List<?> keys) || keys.isEmpty()) {
            // 빈 응답으로 snapshot 을 비우면 전면 401 이 된다 — 기존 LKG 유지(단, 경보).
            log.warn("[alert] JWKS 응답에 keys 가 비어 있습니다 — 기존 snapshot {}개 유지", snapshot.get().size());
            return;
        }
        Map<String, RSAPublicKey> next = new LinkedHashMap<>();
        for (Object k : keys) {
            if (!(k instanceof Map<?, ?> jwk)) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) jwk;
            String kid = asString(entry.get("kid"));
            String kty = asString(entry.get("kty"));
            String n = asString(entry.get("n"));
            String e = asString(entry.get("e"));
            if (kid == null || !"RSA".equals(kty) || n == null || e == null) {
                continue;
            }
            try {
                next.put(kid, toPublicKey(n, e));
            } catch (RuntimeException ex) {
                log.warn("JWKS 키 파싱 실패 (kid={}) — 건너뜁니다", kid, ex);
            }
        }
        if (next.isEmpty()) {
            log.warn("[alert] JWKS 응답에 사용할 수 있는 RSA 키가 없습니다 — 기존 snapshot 유지");
            return;
        }
        Map<String, RSAPublicKey> previous = snapshot.getAndSet(Map.copyOf(next));
        if (!previous.keySet().equals(next.keySet())) {
            log.info("JWKS snapshot 교체: {} -> {}", previous.keySet(), next.keySet());
        }
    }

    private static String asString(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    private static RSAPublicKey toPublicKey(String nB64, String eB64) {
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            BigInteger modulus = new BigInteger(1, decoder.decode(nB64));
            BigInteger exponent = new BigInteger(1, decoder.decode(eB64));
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception e) {
            throw new IllegalArgumentException("JWKS RSA 키 복원 실패", e);
        }
    }

    /** refresh 후에도 kid 부재 — 위조/폐기 키로 간주해 401. */
    public static class UnknownKidException extends RuntimeException {
        public UnknownKidException(String message) {
            super(message);
        }
    }

    /** JWKS 자체를 가져오지 못함 — 의존성 장애로 503. */
    public static class JwksUnavailableException extends RuntimeException {
        public JwksUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
