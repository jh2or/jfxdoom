# Review fixes: branch status and testing

The review fixes are committed on five independent local branches based on
`eb87860`. They have **not** been merged into `main` or `feat/rest-of-todo`.
The original checkout and its uncommitted TODO edits are preserved. Each branch
can be reviewed and tested independently; source changes were also combined in
a disposable detached checkout for compatibility testing, without advancing
any branch. Documentation sections may need reconciliation during a later merge.

| Branch | Local worktree | What changed |
| --- | --- | --- |
| `fix/save-load` | `/tmp/jfxdoom-save-load` | Versioned map/skill/time/flags/RNG metadata, map fingerprint, checksum and structural/index validation before restoration; reconstruct the saved level; reset mover tables and title/demo/HUD state. |
| `fix/gameplay-fidelity` | `/tmp/jfxdoom-gameplay-fidelity` | Reset both RNG streams; separate requested respawn from Nightmare's effective setting; apply/restore fast monster timings and projectile speeds; complete friction, standing animation and no-momentum cheat behavior. |
| `fix/wad-lifecycle` | `/tmp/jfxdoom-wad-lifecycle` | Production mount teardown, replacement/failure handle cleanup, ENDOOM capture before release, independent cache owners and removal of failed partial reads. |
| `fix/frame-scheduling` | `/tmp/jfxdoom-frame-scheduling` | At most four simulation tics per UI pulse, one presentation per batch, proportional wipe advancement, prompt quit guards, non-blocking frame-aligned audio with a bounded backlog. |
| `test/integration-coverage` | `/tmp/jfxdoom-integration-coverage` | Visible diagnostic skips, independently authored image regression, independent real-WAD title oracle, corrected completion claims and this guide. |

These `/tmp` worktrees are convenience checkouts; the committed local branches
remain available even if temporary directories are removed. Use `git worktree
list` to find current locations. A branch already checked out in a worktree
cannot also be checked out in the original working directory.

## Automated checks

Run this from **each** worktree to test that branch, without merging anything:

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

Focused commands, from the corresponding worktree:

```sh
# fix/save-load
bash ./gradlew test --tests doom.system.SaveGameTest --rerun-tasks

# fix/gameplay-fidelity
bash ./gradlew test --tests doom.system.GGameFidelityTest --tests doom.system.GGameDemoDeterminismTest --tests doom.play.PMobjTest --rerun-tasks

# fix/wad-lifecycle
bash ./gradlew test --tests doom.wad.WWadLifecycleTest --rerun-tasks

# fix/frame-scheduling
bash ./gradlew test --tests doom.ui.TicSchedulerTest --tests doom.system.DMainWipeTest --tests doom.sound.BufferedSoundOutputTest --rerun-tasks

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

In each relevant worktree, start a game with:

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
  Make fresh saves on `fix/save-load` and use the same WAD set to restore them.
  Preflight protects against malformed save bytes; independently broken WADs
  or unrelated failures during map construction are not transactional.
- Repeated loads retained old ceiling/platform registrations; old cache owners
  could clear a newly mounted WAD's cache; failed reads could cache partial
  bytes. These were additional bugs found while implementing the requested fixes.
- Animation durations and projectile speeds were immutable although vanilla
  changes them for fast mode. The fix uses original duration baselines instead
  of repeatedly halving/doubling values, avoiding cumulative and odd-value drift.
- Quit-to-launcher is asynchronous. Guarding timer identity alone was insufficient;
  explicit quitting checks now prevent another tick or render after shutdown.
- Sustained rendering overload slows game time relative to wall time; simulation
  commands are not skipped. Sustained audio backpressure may discard the oldest
  queued PCM and can still introduce device-buffer latency. This is a bounded
  policy, not a claim of zero latency or unlimited lossless buffering.
- The existing E1M1 screenshot lacks exact WAD, engine, tic and view provenance.
  New automatic image checks establish patch/title correctness, not full-world
  vanilla pixel equivalence. GPU visual fidelity and subjective sound quality
  still require manual checks; a virtual-display software boot is only a smoke test.
- Other pre-existing scope gaps remain: multiplayer, mouse/joystick input,
  optional graphics precaching, and the object-removal sound-stop call. This review's
  fixes do not constitute a complete vanilla-compatibility certification.
