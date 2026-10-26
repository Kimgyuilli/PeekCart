# D-001 해결 보고서 — Grafana p95/p99 "No data"

> 작성일: 2026-04-10
> 커밋: `fix/d001-metrics-histogram` 브랜치

---

## 문제 요약

Grafana API Response Time 대시보드의 p95/p99 패널에 "No data" 표시.
Prometheus `histogram_quantile()` 함수에 필요한 `http_server_requests_seconds_bucket` 메트릭이 발행되지 않았음.

## 근본 원인 (2단계)

### 1단계: 설정 누락

`management.metrics.distribution.percentiles-histogram.[http.server.requests]: true` 설정이 없어
Micrometer가 histogram bucket 을 생성하지 않음.
`http_server_requests_seconds`가 `summary` 타입으로만 발행됨.

### 2단계: YAML 프로파일 병합 결함

이전 세션에서 YAML에 설정을 추가했으나 동작하지 않음:

| 시도 | 결과 | 원인 |
|------|------|------|
| `application-k8s.yml`에 추가 | 동작 안 함 | `management.metrics` 트리가 base와 profile에 분산, 병합 시 상호 가려짐 |
| `application.yml`(base)에 추가 | 동작 안 함 | k8s 프로파일의 `management.metrics.tags`가 base의 `management.metrics.distribution`을 가려짐 |
| 커맨드라인 프로퍼티 | **동작함** | YAML 바인딩/병합 문제 확정 |

## 해결 방안

### 설계 원칙: YAML vs Java Config 분리

| 구분 | YAML (환경 설정) | Java Config (애플리케이션 동작) |
|------|---|---|
| 역할 | 환경마다 달라지는 연결 정보, 노출 범위 | 환경에 무관한 애플리케이션 행동 |
| 예시 | DB host, Redis host, 엔드포인트 노출 | histogram bucket 활성화, 직렬화 방식 |

`percentiles-histogram`은 모든 환경에서 동일하게 동작해야 하는 **메트릭 행동 설정**이므로
YAML이 아닌 Java `MeterRegistryCustomizer`로 관리하는 것이 정석.

### 구현: `MetricsConfig.java`

```java
// com.peekcart.global.config.MetricsConfig
@Configuration
public class MetricsConfig {
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> httpHistogramCustomizer() {
        return registry -> registry.config().meterFilter(
                new MeterFilter() {
                    @Override
                    public DistributionStatisticConfig configure(
                            Meter.Id id, DistributionStatisticConfig config) {
                        if (id.getName().equals("http.server.requests")) {
                            return DistributionStatisticConfig.builder()
                                    .percentilesHistogram(true)
                                    .build()
                                    .merge(config);
                        }
                        return config;
                    }
                });
    }
}
```

**YAML 파일 변경 없음** — 기존 `application.yml`, `application-k8s.yml` 수정 불필요.

## 검증 결과

### 빌드

- `./gradlew clean build` 전체 테스트 통과 (BUILD SUCCESSFUL)

### minikube 배포 검증

| 검증 항목 | Before | After |
|---|---|---|
| `# TYPE http_server_requests_seconds` | `summary` | `histogram` |
| `_bucket` 메트릭 존재 | 없음 | `http_server_requests_seconds_bucket{...le="0.001"}` 등 정상 발행 |
| Prometheus p95 쿼리 | No data | `/actuator/health/**` ~182ms, `/api/v1/products` ~21ms |

### 배포 시 주의사항

minikube에서 이미지를 교체할 때 `minikube docker-env`를 사용하여 minikube 내부 Docker 데몬에서 직접 빌드해야 함.
호스트 Docker에서 빌드 후 `minikube image load`는 캐시 문제로 이미지가 갱신되지 않을 수 있음.

```bash
eval $(minikube docker-env)
docker build --no-cache -t ghcr.io/kimgyuilli/peakcart:latest .
kubectl rollout restart deployment/peekcart -n peekcart
```

## 변경 파일

| 파일 | 변경 | 이유 |
|------|------|------|
| `src/main/java/com/peekcart/global/config/MetricsConfig.java` | 신규 | histogram 설정을 Java Config로 관리 |

## 잔여 작업

- Grafana UI에서 p95/p99 패널 차트 렌더링 직접 확인 (Prometheus 쿼리 레벨에서는 검증 완료)
- 세션 C GKE 배포 시 동일 설정 자동 적용됨 (Java Config이므로 환경 무관)
