# Stage 1: Build
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
# 멀티모듈 — 의존 해석/설정에 전 모듈 build.gradle 필요 (settings.gradle include 정합). 서비스별 이미지 분리는 PR3.
COPY common/build.gradle common/build.gradle
COPY peekcart-common-observability/build.gradle peekcart-common-observability/build.gradle
COPY peekcart-common-auth/build.gradle peekcart-common-auth/build.gradle
COPY notification-service/build.gradle notification-service/build.gradle
COPY user-service/build.gradle user-service/build.gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src/ src/
COPY common/ common/
COPY peekcart-common-observability/ peekcart-common-observability/
COPY peekcart-common-auth/ peekcart-common-auth/
# 서비스 모듈 소스도 COPY — settings.gradle include 정합(루트 bootJar 설정 단계가 전 모듈 평가).
# 본 이미지는 여전히 root app.jar 만 산출(PR2b). 서비스별 이미지는 PR3.
COPY notification-service/ notification-service/
COPY user-service/ user-service/
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/build/libs/app.jar app.jar

RUN groupadd -r appgroup && useradd -r -g appgroup appuser
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
