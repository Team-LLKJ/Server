FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:go-offline
COPY src src
COPY infra/Healthcheck.java /tmp/Healthcheck.java
RUN ./mvnw -B -q -DskipTests package && javac -d /healthcheck /tmp/Healthcheck.java

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --gid 10001 lit && useradd --uid 10001 --gid lit --no-create-home lit \
    && mkdir -p /app/data && chown -R lit:lit /app && command -v tar
WORKDIR /app
COPY --from=build --chown=lit:lit /build/target/lit-api-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --from=build /healthcheck /app/healthcheck
USER 10001:10001
ENV SERVER_PORT=5174 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 5174
HEALTHCHECK --interval=15s --timeout=6s --start-period=60s --retries=4 CMD ["java","-cp","/app/healthcheck","Healthcheck"]
ENTRYPOINT ["java","-jar","/app/app.jar"]
