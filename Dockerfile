FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S gateway && adduser -S gateway -G gateway
COPY target/api-gateway-1.0.0.jar app.jar
USER gateway:gateway
EXPOSE 9000 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
