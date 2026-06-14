# Stage 1: Build
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
# 멀티모듈 (ADR-0011 PR1) — 의존 해석에 모듈 build.gradle 필요. 서비스별 이미지 분리는 PR3.
COPY common/build.gradle common/build.gradle
COPY peekcart-common-observability/build.gradle peekcart-common-observability/build.gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src/ src/
COPY common/ common/
COPY peekcart-common-observability/ peekcart-common-observability/
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/build/libs/app.jar app.jar

RUN groupadd -r appgroup && useradd -r -g appgroup appuser
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
