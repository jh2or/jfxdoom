# jfxdoom dev container
#
# Base: Debian bookworm + Temurin JDK 21 (CLAUDE.md / build.gradle.kts
# target Java 21 bytecode; Gradle 9.1.0 comes from the wrapper).
FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

# Native libraries JavaFX 21 needs on Linux. The JavaFX *jars* are
# resolved from Maven Central by the openjfx Gradle plugin, but the
# bundled .so files link against the system GTK / X11 / GL stack.
#
#   libgtk-3-0, libglib2.0-0   glass (windowing) backend
#   libgl1, libgl1-mesa-dri    prism es2 pipeline; mesa gives software
#                              GL (llvmpipe) when there is no GPU
#   libxtst6 libxi6 libxrender1 libxxf86vm1  X11 extensions glass uses
#   libfreetype6 libfontconfig1 fonts-dejavu-core  text rendering
#   libasound2                 javax.sound.sampled (SFX) via ALSA
#   xvfb, xauth, x11-utils     headless X server for `gradlew run`
#                              (xvfb-run shells out to xauth)
#
# The base image ships /etc/apt/sources.list.d/yarn.list, whose signing
# key is no longer in the keyring — it makes `apt-get update` fail hard.
# Nothing here needs yarn, so drop the list.
RUN rm -f /etc/apt/sources.list.d/yarn.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        libgtk-3-0 \
        libglib2.0-0 \
        libgl1 \
        libgl1-mesa-dri \
        libglu1-mesa \
        libxtst6 \
        libxi6 \
        libxrender1 \
        libxxf86vm1 \
        libfreetype6 \
        libfontconfig1 \
        fonts-dejavu-core \
        libasound2 \
        xvfb \
        xauth \
        x11-utils \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Pre-create the Gradle home so the named volume mounted there in
# devcontainer.json inherits vscode ownership instead of root's.
RUN mkdir -p /home/vscode/.gradle \
    && chown -R vscode:vscode /home/vscode/.gradle
