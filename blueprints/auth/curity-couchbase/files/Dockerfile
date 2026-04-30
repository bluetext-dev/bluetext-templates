FROM curity.azurecr.io/curity/idsvr:latest

# Pre-built Couchbase data-source plugin — registers `couchbase` as a Curity data-source type.
# Source lives in templates/blueprints/auth/curity-couchbase/files/plugin/ and is published
# to GCS by the plugin's Publishing pipeline (see files/plugin/README.md).
USER root
ADD https://storage.googleapis.com/bluetext-cli-releases/plugins/curity-couchbase-plugin-1.0.3.tar.gz \
    /tmp/curity-couchbase-plugin.tar.gz
RUN mkdir -p /opt/idsvr/usr/share/plugins/couchbase \
 && tar -xzf /tmp/curity-couchbase-plugin.tar.gz -C /opt/idsvr/usr/share/plugins/couchbase --exclude='._*' \
 && rm /tmp/curity-couchbase-plugin.tar.gz \
 && chown -R 10001:10000 /opt/idsvr/usr/share/plugins/couchbase
USER 10001
