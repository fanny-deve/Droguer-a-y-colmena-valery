# ---------- Compilación ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package && java -Djarmode=tools -jar target/pos-core-api-*.jar extract --layers --destination /layers

# ---------- Ejecución (usuario sin privilegios, capas para arranque y despliegue rápidos) ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 10001 appuser
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER appuser
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -Duser.timezone=UTC -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q 200"]
ENTRYPOINT ["java", "-jar", "/app/pos-core-api-0.1.0.jar"]
