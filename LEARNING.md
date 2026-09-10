# Learning and improving Doom levels

The Java-only `doom.ai` component discovers routes, remembers useful input
prefixes, and optimizes successful runs. It needs Java 21 and a Doom 1 WAD;
there are no additional runtime dependencies.

## E1M2 milestone

E1M2's engine par is **75 seconds** (`GGame.pars[1][2]`); the first milestone
was a verified run within **150 seconds / 5,250 tics**. The learner produced
**68.23 seconds / 2,388 tics**, with **10 health**, **14 kills**, the red key,
and **0.26 seconds** of blocked movement. Both fresh-start route replay and the
engine's external-demo playback verified completion. These are gameplay times,
not training wall-clock durations. The generated demo and reports are in
`training/e1m2-milestone/` (ignored by Git).

```sh
# Start a fresh search, with skill 3, normal monsters and a pistol start.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M2 --output training/new-e1m2 --target-seconds 150 --minutes 10"

# Watch the verified milestone recording.
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/e1m2-milestone/winning.lmp"

# Independently verify the selected map, or inspect its input/interaction trace.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M2 --verify training/e1m2-milestone/winning.lmp"
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M2 --analyze training/e1m2-milestone/winning.lmp --output training/e1m2-analysis"

# Reproduce optimization from the algorithm's own earlier learned recording.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M2 --improve src/test/resources/ai/e1m2-learned.lmp --output training/e1m2-reproduce --target-seconds 150 --attempts 16 --minutes 1"
```

The initial seed-1 search found a 568.40-second completion on attempt 8, then
made tiny improvements to 568.34 seconds. That learned input recording became
the regression fixture. The milestone optimizer reached 68.23 seconds on proposal
16 from this fixture. No human route, switch coordinates or exit location was
supplied. Fresh discovery with the final controller has not been benchmarked
across seeds; the result establishes the milestone and replay correctness,
not a guaranteed training time or completion rate. During development, seed 2
found a roughly 328-second route, while seed 3 exhausted its two-minute search
budget without a completion. These were different intermediate builds and are
not a controlled comparison of seed performance.

The changes needed beyond selecting a map were:

- Store keys in archive cells and in visit-history locations. A slower route
  with a key must not be dominated by a faster arrival without it.
- Preserve uncollected key acquisitions while shortcutting an observed route.
- Observe nearby switch texture changes after fresh Use presses. Some optimizer
  variants retain those interactions and reproduce the recorded facing at the
  switch; others try omitting optional switches. Successful re-simulation is
  still required. The controller does not inspect line special/exit IDs.
- Try more local shortcut distances, permit downward waypoints subject to
  clearance, and avoid suppressing a corner detour merely because an unrelated
  door is nearby.
- Allow bounded combat pauses for close enemies obstructing the route, with
  at most 140 tics per continuously selected enemy before resuming movement.

The trace now includes `keys` (six-bit key/card inventory) and `used_line`
(the observed surface index after a switch texture change, or -1). These
surface indexes are learned during replay, not predefined destinations.
Map selection is carried through resets, demo headers, input validation and
checkpoint identity. V1/v2 checkpoints were incompatible with v3; their demos
can still be imported with `--improve` and the matching `--map`.

## Opportunistic equipment collection (v4)

The controller takes short detours to useful, locally reachable armor, weapons
and ammunition. Armor/ammunition must be within 128 map units; a newly acquired
shotgun, chaingun or plasma gun may be within 192. Pickup selection checks line
of sight, vertical reach and the player footprint. Added route distance is
bounded (128 units for supplies, 384 for a new gun); a failed approach expires
after 105 tics and has a 210-tic cooldown. Armor bonuses are only pursued within
32 units. Full armor/ammunition and green armor that would replace substantial
blue armor are ignored.

Without a shotgun, a nearby visible shotgun soldier is given extra target
priority. A bounded fighting interval can kill it; its actual dropped shotgun
then becomes an ordinary pickup target. The engine remains responsible for
kills, drops, pickups and ammunition consumption. Weapon choice is proactive,
even between encounters: usable plasma gun, shotgun, chaingun, pistol, then
chainsaw/fist. An available shotgun with shells is always preferred over the
pistol. Pending changes are respected, and running out of shells selects an
available fallback. Explosive weapons are not added to this local policy.

Armor protection and shell-supply bands distinguish archive cells so a useful
pickup detour is not discarded solely because arrival is slower. V4 checkpoints
serialize these fields and reject earlier archives; existing demos remain
importable. Some optimizer variants also preserve observed acquisitions of
useful guns alongside keys and switch interactions. The runner still retains
the fastest verified completion, so slower successful equipment variants can
have different finishing inventory.

Measured skill-3 E1M2 recordings:

| Recording | Time | Final health | Final armor | Weapon used |
| --- | ---: | ---: | ---: | --- |
| Earlier milestone | 68.23 s | 10 | 0 | Pistol |
| New fastest equipment-aware result | 69.80 s | 91 | 0 | Chaingun (68 rounds fired) |
| New variant collecting both armor and a weapon | 91.26 s | 70 | 81 | Chaingun (31 rounds fired) |

Both new runs passed fresh-start replay and external-demo playback and meet
the 150-second milestone. The 69.80-second run was the first accepted
improvement, on proposal 4 from `e1m2-learned.lmp`; the 91.26-second alternative
was independently evaluated from the same source using the sixth following
variant (proposal index 15). These variants also differ in route and combat,
so the time difference is not a measurement of armor pickup cost alone.

E1M2's shotgun soldiers in this WAD have only the hard-difficulty spawn flag,
so they do not appear at skill 3. Neither of these routes reaches a usable
shotgun pickup. The real-engine regression separately verifies killing a
spawned shotgun soldier with normal inputs, collecting its drop and nearby
armor, then firing the shotgun in a following encounter.

```sh
# Watch the variant that collects armor and the chaingun.
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/e1m2-equipped/winning.lmp"

# Watch the faster chaingun run.
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/e1m2-supplies/winning.lmp"

# Generate the current fastest equipment-aware result from the learned fixture.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M2 --improve src/test/resources/ai/e1m2-learned.lmp --output training/new-equipped --target-seconds 150 --attempts 4 --minutes 1"
```

Both artifact directories contain an `analysis/trace.csv` with armor/type,
owned weapon bitmask, equipped weapon, bullets, shells and cells, alongside the
existing movement, key and interaction data. New artifacts are generated locally
and ignored by Git. The earlier 68.23-second milestone demo remains unchanged.

## Improve an existing learned demo

```sh
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --improve training/e1m1/winning.lmp --output training/e1m1-2min --target-seconds 120 --minutes 2"
```

The supplied demo must actually finish the selected map on the requested difficulty and
within the episode limit. Its actions are replayed to discover the trajectory;
no manually specified route or exit coordinates are needed. Existing v1 archives
are incompatible with the new engine build, but their `.lmp` recordings can be
used with `--improve`.

The optimizer retains a verified incumbent and tries two kinds of proposals:

- Follow and shortcut the observed trajectory with feedback from the current
  simulation. Skip loops when a later observed point is locally reachable.
  Reassess steering every 2–4 tics, including small detours around corners.
- Delete action intervals, favoring long returns to approximately the same
  position and facing. Re-simulate the edited input stream from the beginning:
  removing time changes actor behavior, RNG activity and physics.

A candidate replaces the incumbent only if it completes faster and passes
fresh-start route replay plus the engine's external-demo playback. Failed
proposals preserve the incumbent. Training continues after a first completion
until it meets `--target-seconds` or reaches an overall budget.

## Learn from scratch

```sh
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --output training/new-run --minutes 10 --target-seconds 120"
```

This starts without a demo. The archive explorer observes position, facing,
health, ammunition, owned weapons, local geometry, and nearby monsters with
an unobstructed local ray. Observations use all-around local sensing rather
than screen pixels. No global navigation graph or predefined exit location
is supplied. The archived routes and visit statistics constitute the learned
memory; local movement and combat proposals use programmed heuristics.

Each archive cell combines coarse position/facing, health/ammunition bands,
weapon ownership, key ownership, armor protection, shell supplies, and local passage opening state. Up to two alternatives
favor shorter arrival times and higher health. Selection favors less-visited
locations and less-selected entries; five percent of selections start fresh.
Visit counts are separated by key ownership so returning after a pickup is useful.
Shared prefixes, archive entries, and spatial visit counts are bounded. The
active episode and best successful route have their own bounded storage.

After a first success, most attempts optimize it while occasional attempts
continue archive exploration. An explicit `--improve` run begins directly
with optimization. There is no neural network or exact-world snapshot system.

## Movement and wall handling

- A local swept **square** checks the player's footprint, respecting Doom's
  bounding-box collision, step-height and passage-height constraints. A clear
  center ray alone does not establish that the player fits past a corner.
- Novelty is scored at a **reachable endpoint**, with a bounded bonus. Unvisited
  space behind a solid wall cannot overwhelm the movement feasibility score.
- Short movement bursts allow feedback near corners. Braking considers current
  momentum and clearance; blocked direct paths can use a small local detour.
- Repeated ineffective exploratory movement triggers bounded recovery and a
  temporary penalty for the failed heading.
- Door interaction uses the 64-unit facing ray instead of a global two-second
  timer. It presses Use as soon as a local obstructing surface is reachable,
  releases it, and observes clearance changes to avoid reversing an opening
  door. Missed/ineffective activations have an eight-tic retry interval. Opening
  state is inferred from local geometry; line specials and exit IDs are not read.
  An inherited held Use input is released after restarting an archived prefix.
- In clear stretches the controller offsets its facing and combines forward
  running (up to 50) with sideways movement (up to 40). Both strafe directions
  are supported. A short prediction checks combined thrust, turning, existing
  momentum and the player's footprint before accepting diagonal movement.
  Near obstacles and doors it returns to the approach/braking controller.
- Stationary waiting is measured separately so replacing wall pushing with idle
  time does not appear to be a successful performance improvement.

The learner never teleports, grants health/ammo, enables cheats, or calls the
exit function. It sends legal, demo-quantized commands through the existing
`GGame.gTicker(Ticcmd)` integration. Normal keyboard gameplay is unchanged.

## Watch, verify, and measure

```sh
# Watch the improved run in the normal game window.
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/e1m1-2min/winning.lmp"

# Independent headless verification.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --verify training/e1m1-2min/winning.lmp"

# Measure a demo, including failures, without training.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --analyze training/e1m1-2min/winning.lmp --output training/analysis-fast"
```

Analysis writes `analysis.txt` and `trace.csv`. The trace records position,
facing, health, input commands, and displacement each tic. Summary metrics
include movement-command tics, near-zero movement, turning without movement
input, idle input, Use input, travel distance, and re-entry into 64-unit cells.

The blocked-movement metric counts a tic with nonzero forward/side input and
less than **0.5 map units** of actual displacement. It is a diagnostic proxy,
not a count of wall collisions: actors can also block movement. Idle input may
include legitimate door waiting or stationary combat. Spatial re-entry counts
can include boundary jitter; they are not a count of whole navigation loops.

Measured on the local skill-3 Doom WAD:

| Run | Completion | Near-zero movement during movement input | Remaining health |
| --- | ---: | ---: | ---: |
| Original learned run | 416.23 s | 152.23 s | 46 |
| Optimized from that run | **22.03 s (771 tics)** | **0.14 s (5 tics)** | **77** |
| New exploration without a supplied demo | **108.09 s (3,783 tics)** | **2.09 s** | 12 |

The optimized run passes both fresh-start replay and external `.lmp` playback.
It is below the two-minute target and E1M1's 30-second par. The no-demo experiment
reached its target on attempt 98. These are measurements for this WAD/build/seed,
not guarantees for other settings. The optimizer used the previously learned
run, not an independently supplied human speedrun.

## Door activation and strafing measurements

The changes were implemented and measured in order: door interaction first,
then diagonal movement. On the same skill-3 WAD and seed 1:

| Stage | Completion | Blocked input | Idle input | Health | Forward + strafe tics |
| --- | ---: | ---: | ---: | ---: | ---: |
| Previous optimized demo | 22.03 s / 771 tics | 0.14 s | 5.09 s | 77 | 0 |
| Door-aware activation, forward movement only | 16.23 s / 568 tics | 0.06 s | 1.14 s | 85 | 0 |
| Door-aware activation plus strafing | 13.89 s / 486 tics | 0.57 s | 0.74 s | 91 | 259 |

The door-only run was generated from `training/e1m1-2min/winning.lmp`; the
strafe run was generated from that door-only result. Each succeeded on its
first optimization proposal and passed fresh-start replay plus external-demo
playback. Control runs of the preceding controller from each same input demo,
with one proposal and seed 1, did not improve the incumbent. These are staged
route-optimization measurements, not a statistical benchmark across seeds.
Changing input timing changes steering, combat and RNG activity as well, so
completion-time differences cannot all be assigned to button-press latency.

In the door-only recording, the three doors began opening at tics 118, 444,
and 521, respectively one, two, and one tics after first entering the facing
use ray. The previous recording delayed those activations by up to roughly
two seconds. Door animation still takes time: all 20 blocked-input tics in
the strafe recording occur at the first door while it is gaining clearance.

Artifacts (generated locally and ignored by Git):

- `training/e1m1-doors/winning.lmp`: measured door-only run.
- `training/e1m1-strafe/winning.lmp`: measured final run.
- `training/comparison/doors/` and `training/comparison/strafe/`: analyses,
  input/position traces, and replayed door-event reports.

```sh
# Reproduce the strafe improvement from the saved door-only route.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --improve training/e1m1-doors/winning.lmp --output training/new-strafe --target-seconds 14 --attempts 1 --minutes 1"

# Watch either stage; replace the path with e1m1-doors/winning.lmp to compare.
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/e1m1-strafe/winning.lmp"
```

A separate final-controller search without a supplied demo completed a first
route on attempt 2 (236.54 seconds), then shortened it on attempt 3 to 529 tics
(15.11 seconds, 14 health). Its demo is
`training/e1m1-strafe-newstart/winning.lmp`. This seed-specific result does not
guarantee that another search will finish as quickly.

## Settings, resume and limits

Defaults are skill 3 (Hurt Me Plenty), monsters enabled, seed 1, and a standard
pistol start. Episodes end on exit activation, death, or 21,000 game tics
(ten simulated minutes). Prefix replay counts toward the same episode limit.
Optimization proposals can additionally be pruned when they end without success,
stall, or can no longer improve the incumbent; this is logged as `PRUNED`,
separately from death, timeout and wall-clock interruption.

```sh
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --output training/e1m1-2min --resume --target-seconds 20 --minutes 10"
```

Resume retains the best successful route, archive, counters and learner RNG.
Changing the target time or the invocation's attempt/time budgets is allowed;
other experiment settings must match. If the saved best already meets the
requested target, it is verified and the runner returns. Use `--improve` only
when starting a new experiment, not together with `--resume`.

| Option | Default | Meaning |
| --- | --- | --- |
| `--wad` | required | Doom 1 IWAD containing the selected map |
| `--map` | `E1M1` | Original Doom map, E1M1–E3M9; must exist in the WAD |
| `--output` | `training/<map>` | Experiment or analysis directory; map name lowercased |
| `--resume` | off | Load compatible progress |
| `--improve` | unset | Start from a successful demo |
| `--verify` / `--analyze` | unset | Alternative diagnostic modes |
| `--target-seconds` | `120` | Stop after a verified run at or below this game time; integer 1–600 |
| `--skill` | `3` | Difficulty 1–5 |
| `--episode-tics` | `21000` | Episode limit, 1–21,000 tics |
| `--attempts` | `1000` | Additional attempt/proposal budget for this invocation |
| `--minutes` | `10` | Additional wall-clock training budget |
| `--seed` | `1` | Learner RNG seed, independent of Doom's RNG |
| `--archive-size` | `12000` | Maximum archive entries |
| `--node-limit` | `250000` | Maximum archive prefix nodes; best route stored separately |
| `--cell-size` | `128` | Spatial grouping in map units, 32–512 |

Training has no JavaFX window or audio device. Quote individual paths with
spaces inside `trainArgs`. After `./gradlew installDist`, the runner also works
via `java -cp 'build/install/jfxdoom/lib/*' doom.ai.Trainer ...` with appropriate
classpath quoting for your shell.

Startup, incumbent verification, checkpoint writes and final verification add
overhead outside the simulation budget. Progress is saved every ten attempts,
on accepted improvements, and at orderly termination. Ctrl+C requests a stop
and allows up to ten seconds for saving. A separate watchdog exits on 60 seconds
without progress. Forced termination can lose work since the last checkpoint.
Only one process may own an experiment directory. Independent simulations need
separate processes because the engine's world state is static.

## Artifacts and compatibility

- `archive.bin`: compressed, checksummed v4 checkpoint. Common prefixes are
  serialized once, including the best successful route and optimization counters.
- `manifest.txt`: configuration and SHA-256 WAD/compiled-engine identities.
  Resume rejects mismatches; rebuilds that change classes invalidate archives.
  Launching from a JAR versus class directories also changes engine identity.
- `attempts.csv`: phase, outcome, prefix tics, health/kills, archive statistics,
  elapsed wall time, final key bitmask and movement metrics. Console updates include throughput.
- `latest.lmp`: selected attempts, periodically replaced to bound storage.
- `winning.lmp`: best verified completion, preserved when later proposals fail.
- `result.txt`: its verified time, metrics, target status and experiment identity.

Files are replaced atomically when supported by the filesystem. A demo filename
base must fit the existing eight-character vanilla lump-name limit. A demo's end
marker is never treated as level completion. Diagnostic replay fingerprints
cover gameplay RNG, player inventory/weapon state, actors/references, movers,
lights, sectors and switches; they are not complete world snapshots or a proof
of universal vanilla compatibility. Presentation RNG is excluded.

The input-only learning fixtures and WAD hash are documented in
[`src/test/resources/ai/README.md`](src/test/resources/ai/README.md). The regression
suite uses these fixtures to exercise automatic optimization; normal fresh training
does not load them. No WAD assets are distributed.

## Validation

```sh
./gradlew test --tests 'doom.ai.*'
./gradlew test
```

Real-WAD tests explicitly skip when the local WAD is missing; fixture-specific
checks also require its hash. Tests cover footprint/corner clearance, tangential
movement, escape from contact, changing door/step geometry, movement metrics,
reset/input isolation, recording/replay, timeouts, persistence and corruption,
byte-identical resumed exploration, continued optimization, incumbent retention,
and a verified sub-two-minute shortcut of the original learned route.
