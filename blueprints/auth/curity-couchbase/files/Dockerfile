FROM gradle:8-jdk21 AS plugin-builder
COPY services/curity/couchbase-plugin/ /plugin/
WORKDIR /plugin
RUN gradle createPluginDir

FROM curity.azurecr.io/curity/idsvr:latest
COPY --from=plugin-builder --chown=10001:10000 /plugin/build/curity-couchbase-plugin/ /opt/idsvr/usr/share/plugins/couchbase/
