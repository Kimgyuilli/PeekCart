# PeekCart 서비스 이미지 — 단일 Dockerfile + ARG SERVICE (PR3a, ADR-0011 §이미지 계약)
#
# 5개 서비스(notification/user/product/order/payment)를 하나의 Dockerfile 로 빌드한다.
#   docker build --build-arg SERVICE=<service> -t peekcart-<service>:<tag> .
#
# 멀티모듈 빌드 컨텍스트(B5, memory: project_multimodule_dockerfile_context):
#   gradle 설정 단계가 settings.gradle 의 전 모듈을 평가하므로, 대상 서비스만이 아니라
#   전 모듈의 build.gradle + 소스를 COPY 해야 한다. settings.gradle 에 모듈을 추가/이동하면
#   본 파일의 COPY 목록도 반드시 동기화하고 로컬 `docker build` 로 검증한다.
#
# L-016a: base 이미지는 tag 가 아니라 digest 로 고정한다(재현성).

# Stage 1: Build
FROM eclipse-temurin:17-jdk@sha256:91b6210cce02091f6f0798a83ec51aa223828242c5a21a85793bb8c28dc891c4 AS build
WORKDIR /app

ARG SERVICE
RUN test -n "$SERVICE" || (echo "ERROR: --build-arg SERVICE=<service> 필요" >&2 && exit 1)

# 의존 해석/설정 단계용 — 전 모듈 build.gradle (settings.gradle include 정합)
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY common/build.gradle common/build.gradle
COPY peekcart-common-observability/build.gradle peekcart-common-observability/build.gradle
COPY peekcart-common-auth/build.gradle peekcart-common-auth/build.gradle
COPY notification-service/build.gradle notification-service/build.gradle
COPY user-service/build.gradle user-service/build.gradle
COPY product-service/build.gradle product-service/build.gradle
COPY order-service/build.gradle order-service/build.gradle
COPY payment-service/build.gradle payment-service/build.gradle
RUN chmod +x gradlew && ./gradlew :${SERVICE}:dependencies --no-daemon >/dev/null 2>&1 || true

# 전 모듈 소스 (settings.gradle include 정합 — 설정 단계가 전 모듈 평가)
COPY common/ common/
COPY peekcart-common-observability/ peekcart-common-observability/
COPY peekcart-common-auth/ peekcart-common-auth/
COPY notification-service/ notification-service/
COPY user-service/ user-service/
COPY product-service/ product-service/
COPY order-service/ order-service/
COPY payment-service/ payment-service/
RUN ./gradlew :${SERVICE}:bootJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre@sha256:d226a3132511d057267330beeca73804275899aacdea331b7bd8313bb1506a7a
WORKDIR /app

ARG SERVICE
COPY --from=build /app/${SERVICE}/build/libs/*.jar app.jar

RUN groupadd -r appgroup && useradd -r -g appgroup appuser
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
