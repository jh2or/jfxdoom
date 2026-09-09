# Review fixes: branch status and testing

The five review branches based on `eb87860` are now merged into
`feat/rest-of-todo`, using one separate two-parent merge commit per branch.
Nothing has been merged into `main`. The pre-existing TODO edit is incorporated
in `PORT_STATUS.md`, retaining its findings and marking the completed work.
The source branches and their worktrees remain available for independent review.

| Branch | Local worktree | What changed |
| --- | --- | --- |
| `fix/save-load` | `/tmp/jfxdoom-save-load` | Versioned map/skill/time/flags/RNG metadata, map fingerprint, checksum and structural/index validation before restoration; reconstruct the saved level; reset mover tables and title/demo/HUD state. |
| `fix/gameplay-fidelity` | `/tmp/jfxdoom-gameplay-fidelity` | Reset both RNG streams; separate requested respawn from Nightmare's effective setting; apply/restore fast monster timings and projectile speeds; complete friction, standing animation and no-momentum cheat behavior. |
| `fix/wad-lifecycle` | `/tmp/jfxdoom-wad-lifecycle` | Production mount teardown, replacement/failure handle cleanup, ENDOOM capture before release, independent cache owners and removal of failed partial reads. |
| `fix/frame-scheduling` | `/tmp/jfxdoom-frame-scheduling` | At most four simulation tics per UI pulse, one presentation per batch, proportional wipe advancement, prompt quit guards, function-key mapping, non-blocking frame-aligned audio with a bounded backlog. |
| `test/integration-coverage` | `/tmp/jfxdoom-integration-coverage` | Visible diagnostic skips, independently authored image regression, independent real-WAD title oracle, corrected completion claims and this guide. |

These `/tmp` worktrees are convenience checkouts; the committed local branches
remain available even if temporary directories are removed. Use `git worktree
list` to find current locations. A branch already checked out in a worktree
cannot also be checked out in the original working directory.

## Verification recorded on 2026-09-08

| Checkout | Tests | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| `fix/save-load` | 984 | 984 | 0 | 0 |
| `fix/gameplay-fidelity` | 986 | 986 | 0 | 0 |
| `fix/wad-lifecycle` | 985 | 985 | 0 | 0 |
| `fix/frame-scheduling` | 989 | 989 | 0 | 0 |
| `test/integration-coverage` | 981 | 971 | 10 | 0 |
| Combined patches in detached validation checkout | 1009 | 999 | 10 | 0 |
| Integrated `feat/rest-of-todo` | 1009 | 999 | 10 | 0 |

The ten skips are disabled optional diagnostics. The first four branches retain
baseline diagnostic reporting; the coverage branch makes those skips explicit.
A real JavaFX event loop on a private Xvfb display also passed software gameplay
tick advancement, Escape menu open/close, F10/Y quit, scene replacement, WAD
resource cleanup and verification that no more tics run after quit. The smoke
used JavaFX's software pipeline; it does not verify GPU visuals or audio quality.

## Automated checks

Run this from the `feat/rest-of-todo` checkout to test all integrated fixes,
or from a source-branch worktree to test that branch independently:

```sh
bash ./gradlew test --rerun-tasks --max-workers=2
```

The wrapper is invoked with `bash` because its tracked executable bit is absent.
Use Java 21 or newer and supply your own original Doom WAD. The prepared
worktrees use an ignored symlink to the existing local WAD; it is not committed.
For another checkout, place your WAD in `reference-wads/DOOM.WAD` to enable all
new real-WAD tests. The coverage branch also supports `-Ddoom.wad=/absolute/path`
for its title test and diagnostics; older tests retain their own WAD discovery.
Read the skipped-test count: missing assets do not establish gameplay coverage.
Reports are written under `build/reports/tests/test/`.

All focused commands below work in `feat/rest-of-todo`; they also work in
the corresponding source-branch worktree:

```sh
# fix/save-load
bash ./gradlew test --tests doom.system.SaveGameTest --rerun-tasks

# fix/gameplay-fidelity
bash ./gradlew test --tests doom.system.GGameFidelityTest --tests doom.system.GGameDemoDeterminismTest --tests doom.play.PMobjTest --rerun-tasks

# fix/wad-lifecycle
bash ./gradlew test --tests doom.wad.WWadLifecycleTest --rerun-tasks

# fix/frame-scheduling
bash ./gradlew test --tests doom.ui.TicSchedulerTest --tests doom.ui.JfxDoomAppKeyTest --tests doom.system.DMainWipeTest --tests doom.sound.BufferedSoundOutputTest --rerun-tasks

# test/integration-coverage
bash ./gradlew test --tests doom.ui.VVideoReferenceImageTest --tests doom.system.TitlePageReferenceTest --rerun-tasks
```

The scheduling tests simulate a 60-second clock stall, deferred launcher quit,
and a full/stalled audio device; they do not need real audio hardware. WAD
lifecycle tests also inspect actual descriptors on Linux. Save tests compare
restored serialized state, exercise different maps and title state, and check
that corrupt data leaves the active state unchanged. The demo test compares
actor state through 210 consecutive commands across repeated playback after
unrelated RNG activity; this establishes repeatability, not original-engine
bit equivalence.

The coverage branch's ten optional diagnostics now visibly skip unless enabled.
For a screenshot dump, run separately:

```sh
bash ./gradlew test --tests doom.system.E1M1ScreenshotProbe -De1m1.probe=1 --rerun-tasks
```

Fixture provenance and GPU diagnostic requirements are in
[the renderer reference guide](src/test/resources/render-refs/README.md).

## Manual smoke tests

In the `feat/rest-of-todo` checkout (or a relevant source worktree), start with:

```sh
bash ./gradlew run --args='-iwad reference-wads/DOOM.WAD -warp 1 1'
```

1. **Save/load branch:** change inventory, open a door, save into a spare slot,
   advance to E1M2 (for example with `idclev12`), then load. Check E1M1, position,
   health/ammo and level time. Repeat loading a save made while a door/lift is
   moving. End the game to the title screen and load again. Truncate a *copy*
   in another spare slot; loading it should report failure and leave gameplay
   intact. Automated tests cover corruption without touching personal saves.
2. **Gameplay branch:** add `-respawn`, kill a monster and wait for it to return.
   Compare normal play with `-fast` and `-skill 5`; demons and selected monster
   projectiles should be faster. Start normal games afterward to check normal
   speeds return. Move and release the direction keys; check stopping behavior.
   Repeat title/demo playback after gameplay to look for desynchronization.
3. **WAD branch:** launch, quit to ENDOOM, dismiss to the launcher, and relaunch
   repeatedly. ENDOOM should still appear. While at the launcher there should
   be no WAD handles in `lsof -p <java-pid>`; close the window as well.
4. **Scheduling branch:** use the Launchpad to choose 4× render resolution and
   side-by-side display, then move, fire, open menus and quit. Check input
   responsiveness and sound during load. A debugger pause/resume of the Java
   process should produce bounded catch-up instead of a long frozen burst.
   Wipes should retain their timing. The automated fake-device test verifies
   non-blocking output; listening on real hardware remains necessary to judge
   latency and artifacts.

## Surprises and compatibility limits

- Legacy Java saves lack map identity/time and cannot be restored reliably.
  The new format rejects them explicitly; it also does not read vanilla saves.
  Make fresh saves with the updated engine and use the same WAD set to restore them.
  Preflight protects against malformed save bytes; independently broken WADs
  or unrelated failures during map construction are not transactional.
- Repeated loads retained old ceiling/platform registrations; old cache owners
  could clear a newly mounted WAD's cache; failed reads could cache partial
  bytes. These were additional bugs found while implementing the requested fixes.
- Animation durations and projectile speeds were immutable although vanilla
  changes them for fast mode. The fix uses original duration baselines instead
  of repeatedly halving/doubling values, avoiding cumulative and odd-value drift.
- The real JavaFX smoke check exposed missing F1–F12 key mapping. Function
  keys now reach their engine handlers; F10 → Y is covered by a regression.
- Quit-to-launcher is asynchronous. Guarding timer identity alone was insufficient;
  explicit quitting checks now prevent another tick or render after shutdown.
- Sustained rendering overload slows game time relative to wall time; simulation
  commands are not skipped. Sustained audio backpressure may discard the oldest
  queued PCM and can still introduce device-buffer latency. This is a bounded
  policy, not a claim of zero latency or unlimited lossless buffering.
- The existing E1M1 screenshot lacks exact WAD, engine, tic and view provenance.
  New automatic image checks establish patch/title correctness, not full-world
  vanilla pixel equivalence. GPU visual fidelity and subjective sound quality
  still require manual checks; virtual-display input/quit checks are smoke tests.
- Other pre-existing scope gaps remain: multiplayer, mouse/joystick input,
  optional graphics precaching, and the object-removal sound-stop call. This review's
  fixes do not constitute a complete vanilla-compatibility certification.

## ASCII renderer

Run `./gradlew test --max-workers=2`. `AsciiRendererTest` checks exact bitmap
output, palette color, input immutability, scales 1–4, partial-cell clipping,
replacement of previous glyphs, software-only mode selection, UI overlays and
non-world bypass. The original demo-determinism suite still runs unchanged.

Launch `./gradlew run --args="-iwad reference-wads/DOOM.WAD -warp 1 1 -ascii"`,
or choose ASCII in the Launchpad. Walk, turn and fire: walls, sprites and weapon
should consist of colored glyphs on black. Check HUD text, menus and automap
remain normal graphics. Test windowed/fullscreen, internal scales 1–4, reduced
view size (+/−), level transitions, quit and relaunch into the other renderers.
ASCII is a software post-process; it still incurs the original software render
cost. Internal scales 1× / 2× / 3× / 4× now produce 80 / 160 / 240 / 320
columns across a full-width view. Compare 1× and 3× at the same display size:
the latter should resolve finer details with smaller symbols. The density
regression counts actual rendered glyph rows and columns at each setting.

Branch layout: `feat/rest-of-todo` was restored to `3a9d4e7`; the two subsequent
presentation fixes are preserved on `fix/fullscreen-presentation`. ASCII work
starts at the restored base on `feat/ascii-renderer`, so its 35 Hz presentation
and any pre-existing base-branch issues remain until separately integrated.

Validation: 1,013 tests, 1,003 passed and 10 optional diagnostics skipped.
The actual JavaFX ASCII scene was captured and inspected; glyph contrast was
adjusted after the first capture showed excessive darkness. A virtual-display
smoke check passed live ticks, menu open/close, quit scene, WAD cleanup and timer
shutdown. Native fullscreen performance remains a manual check. The baseline's
known oversized quit-message warning can still occur; its fix is on the separate
presentation branch, as requested.

Density follow-up: all five ASCII tests passed. A live JavaFX run at internal
3× passed and its captured 240-column world was visually inspected at a
1280×800 display size. HUD composition remains unchanged.

Glyph fidelity follow-up: the alphabet now includes 64 bitmap glyphs. Tests
verify exact letter, digit and opposing diagonal stroke patterns, deterministic
output after intervening frames, and at least 30 distinct shapes across a smooth
brightness ramp. Compare textured walls, door frames and weapon outlines at
internal 2× or 3×: letters, digits and punctuation should replace the old small
symbol set. Flat regions use tone mapping; high-contrast cells match local
strokes. Native motion testing remains useful for judging small-glyph readability.
All seven focused ASCII tests passed for this change. The live JavaFX 3× run
passed, and its captured scene was visually checked for the expanded glyph mix.

## Optional depth-based ASCII color fading

Select ASCII and check **Depth-based ASCII color fading**, or launch with
`./gradlew run --args="-iwad reference-wads/DOOM.WAD -warp 1 1 -ascii -ascii-depth"`.
F12 toggles depth fading, which is off by default. The toggle now controls color
intensity instead of glyph size. Test at 2× or 3× internal resolution: approach
and retreat from walls, look along corridors, fire in front of distant geometry,
and approach thin foreground objects. Symbols must retain the same size and
shape while their color fades with depth. Near cells and the weapon stay fully
visible; distant cells/sky retain 28% of normal glyph intensity. HUD,
menus and automap remain unchanged. Hold F12 to verify one toggle per press.

There are ten intensity layers: 100%, 92%, 84%, 76%, 68%, 60%, 52%,
44%, 36%, 28%, with RGB channels rounded to the nearest integer. Nominal
depth boundaries remain 192, 320, 480, 672, 896, 1152, 1472, 1856 and 2304
map units. A 1/6 margin before dimming reduces threshold chatter. Colors fade
toward black; they are not quantized to fewer bits or composited over old frames.

`AsciiRendererTest` compares every glyph pixel across all ten layers, checks
exact 28%-intensity RGB for the far layer, full near color, unchanged glyph
geometry, one-pixel foreground protection, threshold hysteresis, partial-cell
clipping and exact image restoration when disabled. `RenderDepthTest` covers
resolution/detail scaling and weapon/sky overrides; the demo regression compares
actor traces and software-frame checksums with depth capture enabled/disabled.
Native motion and fullscreen performance remain manual playtests.

Validation for the color-fading replacement: all 11 focused ASCII tests passed.
A live JavaFX 3× run passed depth capture, F12 off/on and key-repeat checks; the
captured scene was visually inspected for fixed glyph geometry and fading.

## Diagonal light/dark split at internal 4×

The issue reproduced without Doom geometry or depth fading: a uniform glyph
texture scaled 1280×800 → 1920×1200 with nearest-neighbor sampling developed a
diagonal brightness split. The OpenGL reproducer measured triangle means of
85.28 and 103.08 before the fix, versus 128.02 and 127.55 after. ASCII now uses
filtered sampling at fractional physical display scales; integer scaling stays
sharp and the other renderer modes keep their previous behavior.

The actual JavaFX raster regression needs a display. On Linux with Xvfb/Mesa:

```sh
xvfb-run -a ./gradlew test --tests doom.ui.AsciiScalingTest --tests doom.ui.AsciiRendererTest -Dascii.scaling.probe=1 -Dprism.forceGPU=true
```

Run without the forceGPU flag on normal desktop hardware. The scaling test is
skipped by default on headless test runs. All 12 selected tests passed with the
OpenGL pipeline. Native retest: use internal 4× at the affected window/fullscreen
size and toggle F12; neither mode should have triangular shading. Compare 2×/3×,
resize the window, and check any HiDPI monitor configuration. Fractional scaling
slightly softens glyph edges to remove unstable nearest-neighbor sampling.
