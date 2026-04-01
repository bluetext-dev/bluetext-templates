FROM gradle:8-jdk21 AS wrapper-builder
COPY services/curity/couchbase-jdbc-wrapper/ /wrapper/
WORKDIR /wrapper
RUN gradle wrapperJar

FROM curity.azurecr.io/curity/idsvr:latest

# Couchbase JDBC driver + wrapper go into the JDBC plugin directory (shared classloader)
ADD --chown=10001:10000 \
    https://repo1.maven.org/maven2/com/couchbase/client/couchbase-jdbc-driver/1.0.5/couchbase-jdbc-driver-1.0.5.jar \
    /opt/idsvr/lib/plugins/data.access.jdbc/couchbase-jdbc-driver-1.0.5.jar

COPY --from=wrapper-builder --chown=10001:10000 \
    /wrapper/build/libs/couchbase-postgres-wrapper-1.0.0.jar \
    /opt/idsvr/lib/plugins/data.access.jdbc/couchbase-postgres-wrapper-1.0.0.jar
