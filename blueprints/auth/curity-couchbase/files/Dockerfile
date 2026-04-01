FROM gradle:8-jdk21 AS wrapper-builder
COPY services/curity/couchbase-jdbc-wrapper/ /wrapper/
WORKDIR /wrapper
RUN gradle wrapperJar

FROM curity.azurecr.io/curity/idsvr:latest

# Couchbase JDBC driver (official, from Maven Central)
ADD --chown=10001:10000 \
    https://repo1.maven.org/maven2/com/couchbase/client/couchbase-jdbc-driver/1.0.5/couchbase-jdbc-driver-1.0.5.jar \
    /opt/idsvr/lib/plugins/couchbase-jdbc/couchbase-jdbc-driver-1.0.5.jar

# PostgreSQL dialect wrapper (maps jdbc:postgresql+couchbase:// to jdbc:couchbase:query://)
COPY --from=wrapper-builder --chown=10001:10000 \
    /wrapper/build/libs/couchbase-postgres-wrapper-1.0.0.jar \
    /opt/idsvr/lib/plugins/couchbase-jdbc/couchbase-postgres-wrapper-1.0.0.jar
