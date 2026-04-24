# service-config-manager baked image.
#
# The hostPath mount at /app shadows anything COPYed there, so we bake
# dependencies to locations OUTSIDE /app:
#   - watchexec binary → /usr/bin
#   - Python deps       → /usr/local/lib/python3.11/site-packages
# Both are still available when /app is replaced at runtime with the host
# source tree, and `./bin/run` short-circuits its runtime-install path when
# the tools are already present.

FROM python:3.11.8-slim-bookworm

ARG TARGETARCH

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget ca-certificates \
    && case "${TARGETARCH}" in \
         amd64) WATCHEXEC_ARCH="x86_64" ;; \
         arm64) WATCHEXEC_ARCH="aarch64" ;; \
         *) echo "unsupported arch: ${TARGETARCH}" >&2; exit 1 ;; \
       esac \
    && wget -q "https://github.com/watchexec/watchexec/releases/download/v2.3.2/watchexec-2.3.2-${WATCHEXEC_ARCH}-unknown-linux-musl.deb" -O /tmp/watchexec.deb \
    && dpkg -i /tmp/watchexec.deb \
    && rm /tmp/watchexec.deb \
    && apt-get purge -y --auto-remove wget \
    && rm -rf /var/lib/apt/lists/*

# Pin Python deps into the image's global site-packages so they survive the
# hostPath mount at /app.
COPY code/services/service-config-manager/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --disable-pip-version-check -r /tmp/requirements.txt \
    && rm -f /tmp/requirements.txt

WORKDIR /app
