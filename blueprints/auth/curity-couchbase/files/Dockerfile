FROM gradle:8-jdk21 AS wrapper-builder
COPY services/curity/couchbase-jdbc-wrapper/ /wrapper/
WORKDIR /wrapper
RUN gradle wrapperJar

FROM curity.azurecr.io/curity/idsvr:latest

# Self-contained JDBC wrapper — routes all SQL through N1QL HTTP, no external JDBC driver needed
COPY --from=wrapper-builder --chown=10001:10000 \
    /wrapper/build/libs/couchbase-postgres-wrapper-1.0.0.jar \
    /opt/idsvr/lib/plugins/data.access.jdbc/couchbase-postgres-wrapper-1.0.0.jar
