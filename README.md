# jfxdoom

A Java/JavaFX port of `linuxdoom-1.10` aiming for faithful gameplay. Loads original
Doom WAD files and runs the engine at 35 Hz in a JavaFX window.
Additionally, render resolution and display scaling has been added.

The port works on windows and linux. (Tested Windows 11 and Ubuntu 26.04)

See [`PORT_COMMENTS.md`](PORT_COMMENTS.md) for notes on how the
port was actually built (experiences, deviations from original plans, ...)

## Prerequisites

| Need       | Version                      | Notes                                                  |
| ---------- | ---------------------------- | ------------------------------------------------------ |
| JDK        | 21+                          | Gradle reads `release = 21` for bytecode.              |
| A Doom WAD | retail / shareware / Doom II | `DOOM.WAD`, `DOOM2.WAD`, or the shareware `DOOM1.WAD`. |

Gradle itself is **not** a prerequisite — the bundled wrapper
(`gradlew` / `gradlew.bat`) downloads the right version on first
run.

## Run from source

```
./gradlew run
```

Launched with no arguments, jfxdoom opens a **Launchpad** —
browse to a WAD, click **Launch ▶**, and the engine boots the
same way the original `doom.exe` would (title loop, no level
skip). Two resolution knobs sit in the primary section:

- **Display Resolution** — sizes the window. Windowed 1×–5× the
  logical 320×200 grid, or Fullscreen with letterbox/pillarbox
  bars when the monitor aspect differs from 4:3.
- **Internal Render Resolution** — controls the *software*
  renderer's sample density. 1× is vanilla 320×200; 2× / 3× /
  4× render the walls, floors and sprites at correspondingly
  higher detail. The HUD, status bar, menu and title art always
  render at 320×200 regardless and are composited on top.
  (Greyed out for the pure JavaFX 3D renderer, which draws at
  display resolution; in side-by-side modes it applies to the
  software pane.)
- **Renderer** — which renderer draws the world view:
  - *Software (faithful)* — the ported vanilla renderer
    (default).
  - *JavaFX 3D (experimental)* — GPU scene-graph renderer, see
    below.
  - *Side-by-side (software | 3D)* / *Stacked (software / 3D)* —
    both renderers at once in two equal panes, driven by the
    same game tic.

Flip the **Expert mode** switch to reveal skill / warp /
episode / gameplay flags / demo recording.

To skip the Launchpad and boot straight into the engine, pass
any CLI argument:

```
./gradlew run --args="-iwad reference-wads/DOOM.WAD"
```

Without `-warp` the engine boots into the title-screen demo
loop (Doom 1: TITLEPIC → demo1 → CREDIT → demo2 → HELP2 →
demo3; Doom 2 has its own variant without HELP2) — same as
the original. Press **ESC** to open the menu, **ENTER** on
the title screen as a shortcut into the menu, **F1** for
help, **F8** to toggle messages, **F11** to cycle gamma.

Skip the title loop and drop straight onto a map:

```
./gradlew run --args="-iwad reference-wads/DOOM.WAD -warp 1 1"
./gradlew run --args="-iwad reference-wads/DOOM2.WAD -warp 5"
```

Command-line flags supported (mirroring the C — the Launchpad
exposes the same set):

```
-iwad <path>        primary WAD
-file <path>...     additional PWADs
-warp E M           Doom 1: start at episode E map M
-warp M             Doom 2: start at map M
-skill 1..5         (1 = baby, 5 = nightmare)
-episode 1..4       starting episode (Doom 1)
-nomonsters         empty maps
-respawn            monsters respawn (auto on -nightmare)
-fast               nightmare-speed monsters
-deathmatch         deathmatch mode
-altdeath           altdeath mode
-config <path>      override `default.cfg` location
-record <name>      start recording a .lmp demo
-playdemo <name>    play back a demo lump (single-shot quit on end)
-timedemo <name>    playback alias; accelerated benchmark timing is not implemented
-maxdemo <KB>       grow the recording buffer past the default 128 KiB
-nosound           skip opening audio devices (used by the training runner)
```

Port-specific flags (no C counterpart):

```
-fx3d               use the experimental JavaFX scene-graph renderer
-sbs                side-by-side mode (software pane left, 3D pane right)
-sbsv               stacked mode (software pane top, 3D pane bottom)
```

## The experimental JavaFX 3D renderer

Besides the faithful software renderer, the port ships an
**opt-in** second renderer that draws the world view with
JavaFX's native 3D scene graph (`SubScene` + `PerspectiveCamera`
+ `TriangleMesh`) on the GPU instead of blitting the software
framebuffer. Level geometry is rebuilt from the BSP (closed
subsector polygons), textures/flats/sprites are decoded through
COLORMAP + PLAYPAL into materials, doors/lifts/blinking lights
update live, the sky is a camera-locked cylinder, and the weapon
overlay is still drawn pixel-exact by the software psprite
pipeline. The 2D layers (status bar, HUD, menus, automap,
intermission, screen melt) remain the software implementation on
both renderers.

It is deliberately **not** vanilla-faithful — JavaFX has no
shaders or fog, so the biggest visible difference is the missing
distance-based light diminishing (sectors are lit flat at their
light level; the software renderer darkens surfaces with
distance). Texel filtering is bilinear-ish, and the spectre fuzz
/ invulnerability effects are approximated. Both renderers use the same playsim; the 3D renderer does not implement
a separate simulation. This architecture alone does not establish bit-exact
compatibility with the original engine. Known fidelity fixes and their
regression coverage are tracked in `PORT_STATUS.md`.

The **side-by-side / stacked** modes run both renderers against
the same tic in two equal panes — handy for comparing them; the
lighting difference is immediately visible. Design and status
notes live in [`3D_OVERHAUL.md`](3D_OVERHAUL.md),
[`3D_SIDEBYSIDE_PLAN.md`](3D_SIDEBYSIDE_PLAN.md) and
[`3D_OVERHAUL_STATUS.md`](3D_OVERHAUL_STATUS.md).

## Learning E1M1

A dependency-free Java archive explorer can learn an E1M1 route without opening
a window:

```sh
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --output training/e1m1 --minutes 10"
```

It retains progress across attempts, supports `--resume`, and optimizes successful
runs toward a two-minute target. Use `--improve <demo.lmp>` to improve a previously
learned run. The original 6:56 run was shortened to a verified 22.03 seconds on
the local WAD. See [LEARNING.md](LEARNING.md) for demos, commands, metrics and limits.

## Controls

| Key           | Action                                                                                           |
| ------------- | ------------------------------------------------------------------------------------------------ |
| Arrow keys    | Move / turn                                                                                      |
| `,` / `.`     | Strafe L / R                                                                                     |
| RCtrl         | Fire                                                                                             |
| Space         | Use / open doors                                                                                 |
| RAlt + arrows | Strafe with arrows                                                                               |
| RShift        | Run                                                                                              |
| 1..7          | Weapon select                                                                                    |
| Tab           | Automap                                                                                          |
| Esc           | Menu open / close                                                                                |
| F1..F11       | Help / save / load / volume / detail / quicksave / endgame / messages / quickload / quit / gamma |
| `+` / `-`     | Screen size up / down                                                                            |

Cheat codes (type while playing): `iddqd` (god), `idfa` /
`idkfa` (full arsenal), `idclip` (noclip), `idclev<E><M>`,
`iddt` (automap cheat cycle), `idmus<NN>` (change music),
`idchoppers`, `idmypos`.

## Building a redistributable bundle

`./gradlew run` is the dev path. To produce something you can
hand to someone else without a Gradle install:

### Self-contained launcher directory (recommended)

```
./gradlew installDist
```

The output lands in `build/install/jfxdoom/`. Run it via:

```
build/install/jfxdoom/bin/jfxdoom -iwad path/to/DOOM.WAD
```

(or `bin\jfxdoom.bat` on Windows). The directory bundles every
runtime jar (engine + JavaFX modules + their native libraries
for the host OS) and the launcher script. Zip / tar it up and
hand it to a teammate; no Gradle, no manual classpath setup, no
`module-path` flag wrangling — just a JDK 21 on the receiving
machine.

A tarball / zip suitable for upload:

```
./gradlew distZip          # build/distributions/jfxdoom-0.1.0.zip
./gradlew distTar          # build/distributions/jfxdoom-0.1.0.tar
```

### Single executable installer (`jpackage`)

The JDK ships `jpackage` for producing a native `.exe` / `.msi`
/ `.dmg` / `.deb`. After `installDist`:

```
jpackage --type app-image --name jfxdoom \
         --input  build/install/jfxdoom/lib \
         --main-jar jfxdoom-0.1.0.jar \
         --main-class doom.ui.JfxDoomApp \
         --dest build/installer
```

(For Windows installers use `--type msi` and arrange WiX on
`PATH`; for a Mac dmg use `--type dmg` on a Mac host.)

The bundled WAD isn't part of the package — Doom WAD files are
copyrighted; ship them only with rights to do so. The shareware
`DOOM1.WAD` is freely redistributable and is the safest default
test target.

## Project layout

```
jfxdoom/
├── reference/linuxdoom-1.10/   read-only C source (port reference)
├── src/main/java/doom/
│   ├── system/       boot, main loop, timing, config defaults
│   ├── wad/          WAD loader, zone memory
│   ├── fixed/        fixed-point math
│   ├── render/       software renderer
│   ├── render3d/     experimental JavaFX scene-graph renderer (opt-in)
│   ├── play/         playsim, AI, physics
│   ├── sound/        ISound (javax.sound.sampled) + MUS→MIDI
│   └── ui/           JavaFX shell + Launchpad + menu / status bar / HUD / wipe / finale
├── src/test/java/doom/         JUnit 5 suite (JUnit 5 tests)
├── reference-wads/             your WAD goes here (gitignored)
├── build.gradle.kts            Gradle build script
├── settings.gradle.kts         Gradle project settings
├── gradle.properties           Gradle properties
├── PORT_STATUS.md              per-module port status + deviations list
├── PORT_COMMENTS.md            port-experience notes (human-written)
├── 3D_OVERHAUL.md              3D-renderer design plan + accepted fidelity deviations
├── 3D_OVERHAUL_STATUS.md       3D-renderer implementation status (all phases)
├── 3D_SIDEBYSIDE_PLAN.md       side-by-side mode design plan
├── INITIAL_PROMPT.md           original prompt that bootstrapped the port
├── CLAUDE.md                   live agent instructions (translation rules)
├── LICENSE                     GPL v2
└── README.md                   this file
```

See [`PORT_STATUS.md`](PORT_STATUS.md) for the per-module
breakdown of which C files have landed, what deviates from the
C source (and why), and what's still open (mouse / joystick
input).

## Dev container

`.devcontainer/` describes a Debian bookworm container with JDK 21
and the native GTK / X11 / GL / ALSA libraries JavaFX needs on
Linux. Open the repository in VS Code and choose *Reopen in
Container* (or `devcontainer up --workspace-folder .`).

Creation compiles the project (`./gradlew compileJava
compileTestJava`), so the container is proven to build before you
touch it. From then on the usual commands work unchanged —
`./gradlew test`, `./gradlew installDist`, `./gradlew run`.

The game needs a display. The container includes the `desktop-lite`
feature, which runs an X server on `:1` and serves it over noVNC at
<http://localhost:6080> (password `vscode`) — launch with:

```
.devcontainer/run-doom.sh -iwad reference-wads/DOOM.WAD -warp 1 1
```

and the JavaFX window appears in that browser tab. The script forces
JavaFX's software pipeline (`-Dprism.order=sw`, no GPU in a
container) and falls back to a throwaway Xvfb server if no display is
available.

The virtual screen is 1920×1080×24 (`VNC_RESOLUTION` in
`devcontainer.json`), and the game window is maximized on sight by a
fluxbox rule (`.devcontainer/fluxbox-apps`, installed as
`~/.fluxbox/apps`). Both matter: the stage is non-resizable and sized
to its scene, so whenever the frame ends up smaller than the canvas,
the canvas — centred in a `StackPane` — is clipped on all four sides
instead of scaled. Maximized it is letterboxed instead. `run-doom.sh`
re-asserts the maximize for two minutes, covering the Launchpad → game
scene switch, which resizes the shared stage. WADs are gitignored, so put yours in `reference-wads/` on
the host — the workspace bind mount makes them visible inside.

## Tests

```
./gradlew test
```

The JUnit 5 suite covers math, WAD I/O, rendering primitives, playsim,
sound, menus, intermission, finale, and screen wipes. Disabled diagnostic
probes now report as skipped. WAD-dependent checks need a local WAD;
passing a suite with those checks skipped does not validate real gameplay.

Automatic image checks include an independently authored patch fixture and
a real-WAD title-page comparison against an independent raw-WAD decoder.
They do not prove vanilla world-rendering equivalence. Fixture provenance,
settings and diagnostic commands are documented in
[`render-refs/README.md`](src/test/resources/render-refs/README.md).

The review fixes are merged into `feat/rest-of-todo` with one merge commit
per source branch; `main` is unchanged. See [`FIX_TESTING.md`](FIX_TESTING.md)
for regression commands and manual testing steps.

## License

**jfxdoom** is distributed under the GNU General Public License,
Version 2 — see [LICENSE](LICENSE).

The original DOOM source code (`reference/linuxdoom-1.10/`) is
© 1993-1996 id Software, Inc., released by id Software /
ZeniMax Media in 1999 under GPL v2. Because this Java/JavaFX
port is a derivative work, it inherits the same license.

- DOOM source code © 1993-1996 id Software, Inc., © ZeniMax Media
- jfxdoom (this Java/JavaFX port) © jh2or

**Doom WAD files are not covered by this license.** They are
proprietary content owned by id Software / ZeniMax / Microsoft
and are not redistributed with this repository. The shareware
`DOOM1.WAD` is freely redistributable under id's original
shareware terms; the retail `DOOM.WAD` / `DOOM2.WAD` and
commercial Doom data are not — supply your own copy.
