FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app
COPY gradlew gradlew
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --version
COPY . .
RUN ./gradlew :demo-server:shadowJar --no-daemon -q

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=builder /app/demo-server/build/libs/demo-server-1.0.0-all.jar demo-server.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "demo-server.jar"]
