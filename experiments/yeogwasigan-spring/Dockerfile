# 1단계: 빌드 (로컬에 JDK 가 없어도 docker compose 로 실행할 수 있도록 컨테이너 안에서 빌드)
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /src
COPY settings.gradle build.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon -q

# 2단계: 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
COPY scenarios ./scenarios
RUN mkdir -p results
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
