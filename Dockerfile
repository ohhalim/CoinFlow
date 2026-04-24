# 빌드 스테이지: Gradle로 JAR 생성
FROM gradle:jdk21 AS builder

WORKDIR /app

# 의존성 캐싱을 위해 Gradle 파일 먼저 복사
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY gradlew ./
RUN chmod +x ./gradlew

# 의존성 다운로드 (소스 변경 시 이 레이어는 캐시됨)
RUN ./gradlew dependencies --no-daemon

# 실제 소스 복사 및 빌드
COPY src ./src
RUN ./gradlew clean build -x test --no-daemon

# 실행 스테이지: 경량 JRE 이미지
FROM amazoncorretto:21-alpine-jdk

ENV PROJECT_NAME=coinflow
ENV PROJECT_VERSION=0.0.1-SNAPSHOT
ENV JVM_OPTS=""

WORKDIR /app

COPY --from=builder /app/build/libs/${PROJECT_NAME}-${PROJECT_VERSION}.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Duser.timezone=Asia/Seoul $JVM_OPTS -jar app.jar"]
