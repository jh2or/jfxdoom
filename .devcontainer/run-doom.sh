#!/usr/bin/env bash
# Launch jfxdoom inside the dev container.
#
#   .devcontainer/run-doom.sh                        # Launchpad
#   .devcontainer/run-doom.sh -iwad reference-wads/DOOM.WAD -warp 1 1
#
# Arguments are forwarded verbatim to the application (see README for
# the full flag list).
set -euo pipefail

cd "$(dirname "$0")/.."

# No GPU here: prism's es2 pipeline would run on mesa's llvmpipe.
# Force the software pipeline so behaviour is the same with or
# without the desktop-lite feature installed.
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dprism.order=sw"

args=()
if [ "$#" -gt 0 ]; then
    # Must be --args=<value>, not --args <value>: Doom's flags start
    # with a dash, and Gradle would read the value as another option.
    args=("--args=$*")
fi

# Keep the window maximised for the first two minutes after launch.
# ~/.fluxbox/apps maximises it when it maps, but the Launchpad and the
# game share a single Stage and installing the game scene calls
# sizeToScene(), which can shrink the frame back below the canvas size
# and reintroduce the cropping. Re-asserting covers that switch; after
# the two minutes the window is the user's to arrange.
keep_maximized() {
    local deadline=$(( $(date +%s) + 120 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        wmctrl -r jfxdoom -b add,maximized_vert,maximized_horz 2>/dev/null || true
        sleep 2
    done
}

if [ -n "${DISPLAY:-}" ] && xdpyinfo >/dev/null 2>&1; then
    # desktop-lite's X server is up — the window shows at :6080.
    echo "Using DISPLAY=$DISPLAY (noVNC: http://localhost:6080, password: vscode)"
    ./gradlew run "${args[@]}" &
    game=$!
    keep_maximized &
    keeper=$!
    trap 'kill "$keeper" 2>/dev/null || true' EXIT
    wait "$game"
    exit $?
fi

echo "No usable DISPLAY; starting a private Xvfb server (nothing to look at)."
# Roomier than the default 1280x800 game window, so the frame is never
# constrained (a clipped frame crops the centred canvas on all sides).
exec xvfb-run -a --server-args="-screen 0 1920x1080x24" ./gradlew run "${args[@]}"
