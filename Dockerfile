# One image definition for every service. Build the app on the host first:
#   ./gradlew installDist
# then: docker compose build   (compose passes APP=storage-node, metadata, gateway...)
FROM eclipse-temurin:21-jre-noble

ARG APP

RUN useradd --system --no-create-home --shell /usr/sbin/nologin distrisys \
    && mkdir /data && chown distrisys:distrisys /data

COPY ${APP}/build/install/${APP} /app
# A fixed entrypoint name, so ENTRYPOINT doesn't depend on the build arg.
RUN ln -s /app/bin/${APP} /app/bin/start

USER distrisys
WORKDIR /app
ENV PATH="/app/bin:${PATH}"

# Exec form: the start script runs as PID 1 and itself `exec`s java,
# so the JVM receives SIGTERM from `docker stop` and shuts down gracefully.
ENTRYPOINT ["/app/bin/start"]
