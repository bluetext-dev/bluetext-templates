# Pre-baked Flutter image for the bluetext platform.
#
# Built on top of `ghcr.io/cirruslabs/flutter:stable` with everything that
# the per-pod `bin/init` would otherwise download, compile, or install on
# every cold pod boot:
#
#   - x86_64 multiarch libraries so OrbStack/Rosetta can run the Android
#     SDK's bundled x86 binaries (adb, aapt2, d8, …) on arm64 hosts.
#   - Accepted Android SDK licenses.
#   - Android SDK components Gradle resolves at build time
#     (NDK 28.2.13676358, Build-Tools 35.0.0, Platforms 34 + 35,
#     CMake 3.22.1). Without these in the image, the very first cold
#     pod on a fresh workspace cache spends ~12 min downloading them
#     from Google.
#
# `bin/init`'s symlink-from-image-to-`/sdk-cache` dance handles the case
# where these directories are pre-populated transparently — it copies
# them into the cache volume on first pod boot, then replaces the
# in-image directory with a symlink so subsequent updates persist. With
# this baked image those copies are local-disk-fast (~5 s) and `sdkmanager`
# never needs to phone home.
#
# Final image is intentionally large (~5–7 GB) — Android SDK weighs that
# much. `imagePullPolicy: Never` + `k3d image import` keep it local; only
# the docker build pulls bytes from the network, and only when SDK
# versions bump.

FROM ghcr.io/cirruslabs/flutter:stable

# Pin SDK component versions in one place so future bumps are a single-line edit.
ARG NDK_VERSION=28.2.13676358
ARG BUILD_TOOLS_VERSION=35.0.0
ARG PLATFORM_PRIMARY=34
ARG PLATFORM_SECONDARY=35
ARG CMAKE_VERSION=3.22.1

# 1. x86_64 multiarch — only meaningful when this image is built on arm64,
#    but the apt commands are no-ops on amd64 so we run them unconditionally
#    rather than complicating the build with TARGETARCH conditionals.
RUN dpkg --add-architecture amd64 \
 && (sed -i '/^Architectures:/d; /^Types:/a Architectures: arm64' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || true) \
 && printf '%s\n' \
        'deb [arch=amd64] http://archive.ubuntu.com/ubuntu/ noble main restricted universe multiverse' \
        'deb [arch=amd64] http://archive.ubuntu.com/ubuntu/ noble-updates main restricted universe multiverse' \
        'deb [arch=amd64] http://archive.ubuntu.com/ubuntu/ noble-security main restricted universe multiverse' \
        > /etc/apt/sources.list.d/amd64.list \
 && apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends \
        libc6:amd64 libstdc++6:amd64 zlib1g:amd64 \
 && mkdir -p /usr/lib64 \
 && ln -sf /usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /usr/lib64/ld-linux-x86-64.so.2 \
 && rm -rf /var/lib/apt/lists/*

# 2. Accept Android SDK licenses. `flutter doctor --android-licenses` is
#    interactive; piping `yes` answers every prompt with `y`.
RUN yes | flutter doctor --android-licenses >/dev/null 2>&1 || true

# 3. Pre-install the SDK components Gradle reaches for during a debug build
#    of the OIDC sample app. Versions match what the live RBAC E2E observed
#    Gradle pulling on a cold cache (NDK 28.2, Build-Tools 35, Platforms
#    34 + 35, CMake 3.22). `sdkmanager` ships with the cirrus image at
#    /opt/android-sdk-linux/cmdline-tools/latest/bin and is on $PATH.
RUN sdkmanager --install \
        "ndk;${NDK_VERSION}" \
        "build-tools;${BUILD_TOOLS_VERSION}" \
        "platforms;android-${PLATFORM_PRIMARY}" \
        "platforms;android-${PLATFORM_SECONDARY}" \
        "cmake;${CMAKE_VERSION}" \
    >/dev/null

# 4. Sanity check at build time so a broken sdkmanager fails the build
#    instead of silently shipping an image missing components.
RUN test -f "/opt/android-sdk-linux/ndk/${NDK_VERSION}/source.properties" \
 && test -d "/opt/android-sdk-linux/build-tools/${BUILD_TOOLS_VERSION}" \
 && test -d "/opt/android-sdk-linux/platforms/android-${PLATFORM_PRIMARY}" \
 && test -d "/opt/android-sdk-linux/platforms/android-${PLATFORM_SECONDARY}" \
 && test -d "/opt/android-sdk-linux/cmake/${CMAKE_VERSION}" \
 && test -f /usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 \
 && echo "bluetext-flutter pre-bake OK"
