#!/usr/bin/env bash
# Runs once after the container is created.
set -euo pipefail

cd "$(dirname "$0")/.."

# The workspace is a bind mount; ownership rarely matches, and git
# refuses to operate on "dubious" repositories otherwise.
git config --global --add safe.directory "$PWD" || true

# reference/linuxdoom-1.10 is a nested git repo, so the parent's
# .gitattributes does not govern it. If it was cloned on Windows with
# core.autocrlf=true, every file in it reads as modified from Linux.
if [ -d reference/linuxdoom-1.10/.git ]; then
    git config --global --add safe.directory "$PWD/reference/linuxdoom-1.10" || true
    git -C reference/linuxdoom-1.10 config core.autocrlf false
    if [ -n "$(git -C reference/linuxdoom-1.10 status --porcelain)" ]; then
        echo "note: reference/linuxdoom-1.10 has CRLF working-tree files."
        echo "      renormalize (endings only, no content change) with:"
        echo "      (cd reference/linuxdoom-1.10 && git ls-files -z | xargs -0 rm -- && git checkout -- .)"
    fi
fi

# Best-effort: a bind mount from a Windows host reports fixed
# permissions and rejects chmod outright (the files are already
# executable there, and the exec bit is recorded in git besides).
chmod +x gradlew .devcontainer/*.sh 2>/dev/null || true

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
