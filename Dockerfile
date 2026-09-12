FROM eclipse-temurin:25.0.2_10-jdk-noble AS build
WORKDIR /source
COPY . .
ARG SERVICE
RUN test -n "$SERVICE" && case "$SERVICE" in gateway|catalog|inventory|order|payment|payment-simulator) ;; *) exit 2;; esac \
    && sh ./gradlew --no-daemon ":services:${SERVICE}:bootJar" \
    && find "services/${SERVICE}/build/libs" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp '{}' /application.jar \;

FROM eclipse-temurin:25.0.2_10-jre-noble AS runtime
RUN groupadd --gid 10001 odexa && useradd --uid 10001 --gid odexa --no-create-home odexa
WORKDIR /app
COPY --from=build --chown=10001:10001 /application.jar /app/application.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=4s --start-period=40s --retries=12 \
    CMD bash -ec 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080}; printf "GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; grep -q '\''"status":"UP"'\'' <&3'
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
