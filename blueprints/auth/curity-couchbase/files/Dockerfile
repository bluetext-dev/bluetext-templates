FROM curity.azurecr.io/curity/idsvr:latest

# Add Couchbase JDBC driver (official, from Maven Central)
ADD --chown=10001:10000 \
    https://repo1.maven.org/maven2/com/couchbase/client/couchbase-jdbc-driver/1.0.5/couchbase-jdbc-driver-1.0.5.jar \
    /opt/idsvr/lib/plugins/couchbase-jdbc/couchbase-jdbc-driver-1.0.5.jar
