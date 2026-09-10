# Learning and improving E1M1

The Java-only `doom.ai` component discovers E1M1 routes, remembers useful input
prefixes, and optimizes successful runs. It needs Java 21 and a Doom 1 WAD;
there are no additional runtime dependencies.

## Improve an existing learned demo

```sh
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --improve training/e1m1/winning.lmp --output training/e1m1-2min --target-seconds 120 --minutes 2"
```

The supplied demo must actually finish E1M1 on the requested difficulty and
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
weapon ownership, and local passage opening state. Up to two alternatives
favor shorter arrival times and higher health. Selection favors less-visited
locations and less-selected entries; five percent of selections start fresh.
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
- Closed two-sided passages receive a bounded interaction attempt and cooldown.
  Use is pulsed and released, allowing the door to move. Rapid repeated Use can
  reverse a moving Doom door and prevent it from opening far enough.
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
| `--wad` | required | Doom 1 IWAD containing E1M1 |
| `--output` | `training/e1m1` | Experiment or analysis directory |
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

- `archive.bin`: compressed, checksummed v2 checkpoint. Common prefixes are
  serialized once, including the best successful route and optimization counters.
- `manifest.txt`: configuration and SHA-256 WAD/compiled-engine identities.
  Resume rejects mismatches; rebuilds that change classes invalidate archives.
  Launching from a JAR versus class directories also changes engine identity.
- `attempts.csv`: phase, outcome, prefix tics, health/kills, archive statistics,
  elapsed wall time and movement metrics. Console updates include throughput.
- `latest.lmp`: selected attempts, periodically replaced to bound storage.
- `winning.lmp`: best verified completion, preserved when later proposals fail.
- `result.txt`: its verified time, metrics, target status and experiment identity.

Files are replaced atomically when supported by the filesystem. A demo filename
base must fit the existing eight-character vanilla lump-name limit. A demo's end
marker is never treated as level completion. Diagnostic replay fingerprints
cover gameplay RNG, player inventory/weapon state, actors/references, movers,
lights, sectors and switches; they are not complete world snapshots or a proof
of universal vanilla compatibility. Presentation RNG is excluded.

The initial input-only learning fixture and WAD hash are documented in
[`src/test/resources/ai/README.md`](src/test/resources/ai/README.md). The regression
suite uses this fixture to exercise automatic optimization; normal fresh training
does not load it. No WAD assets are distributed.

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
