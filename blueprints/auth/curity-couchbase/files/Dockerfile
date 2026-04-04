FROM curity.azurecr.io/curity/idsvr:latest

# Pre-built JDBC wrapper — routes all SQL through Couchbase N1QL HTTP
ADD --chown=10001:10000 \
    https://storage.googleapis.com/bluetext-cli-releases/plugins/couchbase-postgres-wrapper-1.0.0.jar \
    /opt/idsvr/lib/plugins/data.access.jdbc/couchbase-postgres-wrapper-1.0.0.jar
