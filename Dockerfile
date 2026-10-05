# ---- build stage -----------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

# ---- runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre
# Run as an unprivileged user; data (AOF) lives in a dedicated volume.
RUN useradd --system --uid 10001 redcake && mkdir /data && chown redcake /data
USER redcake
WORKDIR /data
COPY --from=build /src/target/redCake-*.jar /opt/redcake/redcake.jar

EXPOSE 6379 9100
VOLUME /data

# Healthy = the metrics endpoint answers 200 on /health (503 when the AOF cannot be written).
# Pure bash, so no curl is needed in the image.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 CMD \
  bash -c 'exec 3<>/dev/tcp/127.0.0.1/9100 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200 "'


# Containers must listen on all interfaces, so protected mode needs a key:
#   docker run -e REDCAKE_API_KEY=... redcake
# (or add --allow-insecure when the network is trusted).
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/redcake/redcake.jar --bind 0.0.0.0 \"$@\"", "--"]
CMD ["--aof-file", "/data/redcake.aof", "--metrics-port", "9100"]
