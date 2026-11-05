FROM gradle:9.7.1-jdk25 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src/main src/main
RUN gradle --no-daemon installDist

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 yonto \
    && useradd --system --uid 10001 --gid yonto --home-dir /data --shell /usr/sbin/nologin yonto \
    && mkdir -p /data && chown yonto:yonto /data
COPY --from=build /src/build/install/yonto-sync-server /opt/yonto-sync-server
USER yonto
VOLUME /data
ENV SYNC_DB=/data/sync.db PORT=8080
EXPOSE 8080
ENTRYPOINT ["/opt/yonto-sync-server/bin/yonto-sync-server"]
