FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-Duser.timezone=UTC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

RUN apk add --no-cache tzdata \
    && addgroup -g 10001 -S app \
    && adduser -u 10001 -S app -G app

ARG JAR_FILE=target/*.jar
COPY --chown=app:app ${JAR_FILE} app.jar

USER 10001:10001
EXPOSE 9001 9090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
