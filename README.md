# jfxdoom

A faithful Java/JavaFX port of `linuxdoom-1.10`. Loads original
Doom WAD files and runs the engine at 35 Hz in a JavaFX window.

## Prerequisites

| Need                 | Version       | Notes                                     |
|----------------------|---------------|-------------------------------------------|
| JDK                  | 21+           | Gradle reads `release = 21` for bytecode. |
| Gradle               | 8.x           | Bundled wrapper at `gradlew` / `gradlew.bat`. |
| A Doom WAD           | retail / shareware / Doom II | `DOOM.WAD`, `DOOM2.WAD`, or the shareware `DOOM1.WAD`. |

Place the WAD anywhere readable; the path is supplied via `-iwad`.
A common spot is `reference-wads/DOOM.WAD` next to the source.

## Run from source

```
gradlew run
```

Launched with no arguments, jfxdoom opens a **Startup UI** —
browse to a WAD, click **Launch ▶**, and the engine boots the
same way the original `doom.exe` would (title loop, no level
skip). Flip the **Expert mode** switch to reveal skill / warp /
episode / gameplay flags / demo recording.

To skip the UI and boot straight into the engine, pass any
CLI argument:

```
gradlew run --args="-iwad reference-wads/DOOM.WAD"
```

Without `-warp` the engine boots into the title-screen demo loop
(TITLEPIC → demo1 → CREDIT → demo2 → HELP2 → demo3) the same way
the original does. Press **ESC** to open the menu, **F1** for
help, **F8** to toggle messages, **F11** to cycle gamma.

Skip the title loop and drop straight onto a map:

```
gradlew run --args="-iwad reference-wads/DOOM.WAD -warp 1 1"
gradlew run --args="-iwad reference-wads/DOOM2.WAD -warp 5"
```

Command-line flags supported (mirroring the C — the Startup
UI exposes the same set):

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
-timedemo <name>    same, but bail with timing info at end
-maxdemo <KB>       grow the recording buffer past the default 128 KiB
```

## Controls

| Key                           | Action                  |
|-------------------------------|-------------------------|
| Arrow keys                    | Move / turn             |
| `,` / `.`                     | Strafe L / R            |
| RCtrl                         | Fire                    |
| Space                         | Use / open doors        |
| RAlt + arrows                 | Strafe with arrows      |
| RShift                        | Run                     |
| 1..7                          | Weapon select           |
| Tab                           | Automap                 |
| Esc                           | Menu open / close       |
| F1..F11                       | Help / save / load / volume / detail / quicksave / endgame / messages / quickload / quit / gamma |
| `+` / `-`                     | Screen size up / down   |

Cheat codes (type while playing): `iddqd` (god), `idfa` /
`idkfa` (full arsenal), `idclip` (noclip), `idclev<E><M>`,
`iddt` (automap cheat cycle), `idmus<NN>` (change music),
`idchoppers`, `idmypos`.

## Building a redistributable bundle

`gradle run` is the dev path. To produce something you can hand
to someone else without a Gradle install:

### Self-contained launcher directory (recommended)

```
gradlew installDist
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
gradlew distZip          # build/distributions/jfxdoom-0.1.0.zip
gradlew distTar          # build/distributions/jfxdoom-0.1.0.tar
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
│   ├── play/         playsim, AI, physics
│   ├── sound/        ISound (javax.sound.sampled) + MUS→MIDI
│   └── ui/           JavaFX shell + menu / status bar / HUD / wipe / finale
├── src/test/java/doom/         JUnit 5 suite (≈955 tests)
├── reference-wads/             your WAD goes here (gitignored)
├── PORT_STATUS.md              per-module port status + deviations list
└── README.md                   this file
```

See `PORT_STATUS.md` for the per-module breakdown of which C
files have landed, what deviates from the C source (and why),
and what's still open (ENDOOM screen, mouse / joystick input).

## Tests

```
gradlew test
```

≈955 JUnit 5 tests covering math, WAD I/O, rendering primitives,
playsim, sound, menu, intermission, finale, and the screen-wipe
state machine.

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
