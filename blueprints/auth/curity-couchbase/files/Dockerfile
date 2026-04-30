# Stage 1: compile the Couchbase data-source plugin from source.
# Source lives at code/services/curity/plugin/ in the system, copied there by the blueprint's tree_copy step.
# Docker build context is the system root, so paths are system-relative.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY code/services/curity/plugin/ ./
RUN chmod +x gradlew && ./gradlew --no-daemon createPluginDir

# Stage 2: Curity runtime image with the compiled plugin installed.
# The createPluginDir task outputs to build/<rootProject.name>/, where rootProject.name = curity-couchbase-plugin.
FROM curity.azurecr.io/curity/idsvr:latest
COPY --from=builder --chown=10001:10000 \
    /build/build/curity-couchbase-plugin/ \
    /opt/idsvr/usr/share/plugins/couchbase/
