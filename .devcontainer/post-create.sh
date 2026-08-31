#!/usr/bin/env bash
# Runs once after the container is created.
set -euo pipefail

cd "$(dirname "$0")/.."

# The workspace is a bind mount; ownership rarely matches, and git
# refuses to operate on "dubious" repositories otherwise.
git config --global --add safe.directory "$PWD" || true

chmod +x gradlew .devcontainer/*.sh

# Downloads the Gradle 9.1.0 distribution named in
# gradle/wrapper/gradle-wrapper.properties, resolves JavaFX 21.0.4
# (linux natives) and JUnit, then compiles main + test sources.
./gradlew --no-daemon compileJava compileTestJava

cat <<'EOF'

jfxdoom dev container ready.

  ./gradlew test                       run the JUnit suite
  ./gradlew installDist                build the launcher in build/install/jfxdoom
  .devcontainer/run-doom.sh            launch the game (see notes below)

The GUI needs a display. desktop-lite provides one on :1 — open
http://localhost:6080 (password: vscode) and you will see the
JavaFX window there. Without a viewer, run-doom.sh falls back to a
throwaway Xvfb server so the app still starts.

WAD files are not in git; drop DOOM.WAD / DOOM2.WAD / DOOM1.WAD into
reference-wads/ on the host — the workspace bind mount makes them
visible here. Several tests and probes look for them.
EOF
