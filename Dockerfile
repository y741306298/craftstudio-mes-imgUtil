FROM maven:3.9.9-eclipse-temurin-22 AS build
WORKDIR /build
COPY . .
RUN mvn -B -ntp verify

FROM eclipse-temurin:22-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends fonts-noto-cjk fontconfig curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 graphics && useradd --uid 10001 --gid graphics --no-create-home graphics \
    && mkdir -p /app/data && chown -R graphics:graphics /app
WORKDIR /app
COPY --from=build /build/craftstudio-imgutil-interfaces/target/craftstudio-imgutil-interfaces-0.0.1-SNAPSHOT.jar app.jar
USER graphics
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m -XX:MaxDirectMemorySize=64m -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
ENV SERVER_ADDRESS=0.0.0.0 SPRING_PROFILES_ACTIVE=prod GRAPHICS_DATA_DIRECTORY=/app/data
EXPOSE 8092
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD curl -fsS http://127.0.0.1:8092/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
