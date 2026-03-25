FROM gradle:8-jdk21 AS plugin-builder
RUN git clone https://github.com/curityio/couchbase-data-access-provider.git /plugin
WORKDIR /plugin
RUN ./gradlew createPluginDir

FROM curity.azurecr.io/curity/idsvr:latest
COPY --from=plugin-builder --chown=10001:10000 /plugin/build/curity-couchbase-plugin/ /opt/idsvr/usr/share/plugins/couchbase/
