# Learning and improving Doom levels

The Java-only `doom.ai` component discovers routes, remembers useful input
prefixes, and optimizes successful runs. It needs Java 21 and a Doom 1 WAD;
there are no additional runtime dependencies.

New `.lmp` files are local artifacts ignored by Git. Existing tracked E1M1–E1M3
fixtures remain versioned; tests using the newer recordings skip when those files
are absent. The linked demo bundles are available in the generating workspace.

## All nine Episode 1 maps within twice par

Every map, including secret level **E1M9**, now has a replay-verified completion
within **2× its engine par time**. These are independent **skill-3 pistol starts**
with normal monsters and ordinary player inputs. E1M8 defeats both Barons and
reaches episode victory; E1M9 returns normally to E1M4.

| Map | Previous time | Current time | 2×par limit |
|---|---:|---:|---:|
| [E1M1](training/episode1-faster/m1win.lmp) | 0:13.89 | 0:13.89 | 1:00 |
| [E1M2](training/episode1-faster/m2win.lmp) | 0:54.09 | 0:54.09 | 2:30 |
| [E1M3](training/episode1-faster/m3win.lmp) | 1:02.43 | 1:02.43 | 4:00 |
| [E1M4](training/episode1-faster/m4win.lmp) | 1:00.97 | 1:00.97 | 3:00 |
| [E1M5](training/episode1-faster/m5win.lmp) | 9:40.51 | **5:22.94** | 5:30 |
| [E1M6](training/episode1-faster/m6win.lmp) | 7:46.03 | **3:45.60** | 6:00 |
| [E1M7](training/episode1-faster/m7win.lmp) | 8:59.31 | **3:26.31** | 6:00 |
| [E1M8](training/episode1-faster/m8win.lmp) | 4:09.63 | **0:58.37** | 1:00 |
| [E1M9](training/episode1-faster/m9win.lmp) | 7:38.29 | **4:13.97** | 5:30 |

The [demo bundle and report](training/episode1-faster/README.md) contains all
nine recordings, per-map verification JSON, movement traces, hashes and research
provenance. The [ZIP](training/episode1-faster/demos.zip) includes no WAD data.
The earlier `training/episode1-complete/` bundle is retained for comparison.

```sh
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/episode1-faster/m8win.lmp"
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M8 --verify training/episode1-faster/m8win.lmp"
```

E1M5 uses partial-route optimization: replay a known prefix, then try faster
feedback-controlled suffixes. Turning variants use ordinary quantized mouse-style
inputs; running and strafe limits remain unchanged. E1M6 benefited from facing
the actual demonstrated switch panel rather than extrapolating the old viewing
angle. E1M7 and E1M9 continue faster key-acquisition prefixes discovered in earlier
trials, using the existing archive explorer.

E1M8 needed additional offline experiments. A route through the chaingun and
nearby ammunition was generated from live item positions and learned interaction
landmarks. Prefix shortening reduced its preparation from 704 to 612 tics while
retaining ammunition, equipment and observed mechanism activations. A bounded
beam search then replayed alternative movement/firing sequences against the two
Barons. The selected fight clears them at 1,268 tics; geometry-based navigation
through the learned switch and teleport entrance finishes at 2,043 tics.
The research harness uses enemy positions/health from the simulation and is
saved with the bundle; it is **not yet part of the normal `train` command**.

The production optimizer now supports preserved-prefix suffix trials and faster
turning. Local floor/ceiling-motion memory allows experiments to retain observed
walk-triggered mechanisms without reading line specials or tags. Rejected
experiments were not retained in the controller. These are best-of-search
recordings, not a guarantee that a fresh search will achieve these times within
a particular training budget. All candidates are re-simulated from player inputs;
no saved inventory, actor state or RNG state is injected.

## E1M9 staircase and locked-door corrections

A separate [revised E1M9 demo](training/e1m9-stair-fix/m9clean.lmp) addresses the
staircase reversals and repeated red-door attempts observed in the fastest demo.
The [comparison report](training/e1m9-stair-fix/README.md) retains both recordings.

| Measure | Previous fastest | Revised behavior |
|---|---:|---:|
| Completion | 4:13.97 | 4:36.94 |
| Red-door rejections | 31 | 1 |
| Rapid returns across a stair boundary | 291 | 62 |
| Final health | 20 | 59 |

Both runs are skill-3 pistol starts and meet E1M9's 5:30 twice-par limit. The
fastest-demo bundle above is unchanged. The new behavior is substantially less
repetitive but does not eliminate every backtrack, and this selected run is
22.97 seconds slower. The recrossing metric counts an opposite-direction return
across the same pair of stair sectors within 105 tics; it is not a count of whole
staircase laps and can include useful combat movement.

The cause was in `MotionProbe.door`: it compared a distant boundary's height
with the player's current feet, so later 16-unit risers looked like blocked
doors. `Explorer` interrupted navigation to pursue them. Door selection and Use
rays now distinguish actual boundary obstructions from traversable stair steps.
A genuine closed door or raised lift is still approached and tested normally.

`DoorMemory` learns a door's required key color from the first fresh, displayed
engine denial. The requirement survives kills, unrelated key pickups and
controller restarts, and releases when the matching card or skull is acquired.
Replay reconstructs this memory from the prefix's actual inputs. The navigator
immediately invalidates a cached path through a newly denied door; local Use
suppression also respects the lock. Neither mechanism reads line special IDs.

Three controlled same-prefix/same-seed trials reduced time pursuing false stair
doors from 576/1261/802 tics to zero and red denials from 48/43/35 to 1/2/1.
The two denials in the second revised trial are at different doors. All six
controlled trials died, so this is evidence about the targeted behaviors, not
an improved completion probability. A separate bounded archive search found the
new completion at attempt 111, then 1,000 optimization proposals shortened it.

Future pistol-start searches should defer a level after several thousand
unsuccessful attempts at twice par, retaining its best demo and useful prefixes.
This fix used 680 archive attempts, 1,000 optimization proposals, 1,008 direct
follow/splice proposals and six controlled trials (2,694 total candidates).

## Current planning and engine compatibility

The final controller uses **full level geometry, key positions and switch
appearance**. This is more information than the earlier local-only controller
had. `GeometryNavigator` plans on a 16-unit grid bounded to 300,000 cells. It
checks the player's footprint along edges, respects stairs and ceilings, and
plans detours around solid decorations. Closed doors and possible raised lifts
are hypotheses: ordinary Use inputs must make them traversable. Failed barriers
are remembered until keys, observed progress or their geometry changes. The
controller never reads exit specials/tags or follows a human-authored route.

The archive samples key states before spatial novelty within them, so a rare
new key is not diluted by hundreds of earlier locations. Switch texture changes
and kill progress distinguish archived states; shortened prefixes must preserve
observed interactions. The planner receives switch updates during play and replay,
recognizes both initial switch states, and faces an intended switch instead of
steering around its wall.

Planning prefers dry routes over visible `NUKAGE`/`LAVA` floors and does not choose
such floors merely as exploration destinations. Necessary crossings and drops
remain possible. Combat uses bounded moving engagements; explosive barrels in
the route are shot from outside the blast radius, with a retreat attempt when
needed. The existing nearby equipment policy remains; the rejected low-health
recovery and bonus-sweeping behaviors are absent. All control remains Java-only.

This work uncovered two engine translation errors. `PSpec.pPlayerInSpecialSector`
was an empty stub: floor damage, secret-sector counting and E1M8's ending were
missing. Its implementation now follows the original C, including radiation-suit
random-number consumption and the finale's normal exit condition. `Tables.slopeDiv`
now uses unsigned division and comparison, preventing negative angle-table indexes
on large maps. These restore engine behavior rather than change the rules for
the learner.

**Historical results below describe their original engine builds.** Restoring floor
damage changes some old recordings. The fast E1M1, E1M3 and E1M4 recordings still
complete. The old slow E1M2/E1M3 regression recordings do not; they have been
replaced with verified current-engine fixtures. E1M2's new fixture completes in
1,893 tics / 54.09 seconds. E1M3's replacement is the previously optimized
2,185-tic / 62.43-second recording. Their completion tests now verify these
recordings; the separate E1M1 and unfinished-prefix tests exercise shortening.

Version-5 checkpoints are incompatible with older checkpoint formats and reject
mismatching engine builds. To reuse an algorithm-recorded trial across builds,
`--seed-demo` re-simulates its legal inputs and imports only surviving prefixes:

```sh
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M7 --seed-demo training/prior/short.lmp --output training/continued --target-seconds 600 --minutes 12"
```

A seed may be unfinished, end in death/timeout, or complete the map. It never
imports snapshots, grants equipment or revives the player. The output preserves
`seed.lmp`; a supplied seed is part of the experiment's provenance. `--improve`
continues to require an already successful recording. Neither mode is a fresh
unseeded search. Independent trainer processes may run concurrently with separate
output directories; their static engines and archives are isolated.

## E1M4 milestone

The **existing controller solved E1M4 without algorithm changes**. A fresh
seed-6 search found a first completion on attempt 187 at 6,343 tics
(**181.23 seconds**), then automatically optimized it on attempt 219 to
**2,134 tics / 60.97 seconds**. This is below both E1M4's **90-second par** and
the initial **180-second** target. The final run finishes through the normal
exit to **E1M5**, with **84 health, 99 armor, a shotgun, eight shells and 12 kills**.
It collects both the blue and yellow keys. Blocked movement totals **0.43 seconds**.

This experiment used skill 3, normal monsters and a standard pistol start, with
no supplied demo, human route or map-specific controller rules. Learning includes
automatic shortening of discovered prefixes and optimization after completion.
The seed-6 process found its first completion about **54 wall-clock seconds**
into training and the final run at about **59 seconds**. Eight independent searches
were started in two batches; these timings describe that process, not total CPU
cost or a guarantee across seeds. Remaining searches were stopped after success.

A separate fresh seed-2 search completed on attempt 366 in
**4,406 tics / 125.89 seconds**, with **51 health, 93 armor and 15 kills**.
That recording is saved as `training/e1m4-milestone/winning.lmp`.
The recommended faster recording is `training/e1m4-optimized/winning.lmp`;
it came from the seed-6 search, **not** from optimizing the seed-2 recording.
Both pass fresh route replay and the engine's external demo loader.

```sh
# Watch the recommended run.
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/e1m4-optimized/winning.lmp"

# Independently verify its completion.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M4 --verify training/e1m4-optimized/winning.lmp"

# Repeat the successful fresh search with no supplied demo.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M4 --seed 6 --target-seconds 180 --attempts 1000 --minutes 4 --output training/new-e1m4"
```

Per-attempt logs and manifests are under `training/e1m4-search/seed*/`.
`training/e1m4-search/summary.json` records the experiment outcomes and demo
hashes. Both selected artifact directories include `verification.json` and
`analysis/trace.csv`. Generated training archives remain ignored by Git.
The input-only `e1m4-learned.lmp` regression fixture preserves the faster result;
its matching-WAD test checks completion below par, both keys, deterministic replay
and the normal next-level transition. It is not loaded by fresh training.

## E1M3 milestone

The final fresh seed-2 search reached the **normal exit to E1M4** in
**198.00 seconds / 6,930 tics**, below twice E1M3's 120-second par
(**240 seconds**). It used skill 3, normal monsters and a pistol start,
finishing with **23 health, 68 armor, a shotgun, and 42 kills**. The blue key
was collected; the yellow key and secret exit were unnecessary. Both fresh
route replay and the engine's external demo loader verified completion.

This first completion was exploration attempt **224**, approximately **234
seconds** into that final experiment. The search began without a supplied
demo and shortened its own intermediate discoveries before finding an exit.
Earlier development trials failed; this timing describes the final experiment,
not the whole development effort or a guarantee across seeds. A separate final
seed-1 search did not finish within its four-minute training budget.

```sh
# Repeat a fresh search with the successful settings.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M3 --output training/new-e1m3 --seed 2 --target-seconds 240 --attempts 1000 --minutes 4"

# Watch or independently verify the milestone.
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/e1m3-milestone/winning.lmp"
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M3 --verify training/e1m3-milestone/winning.lmp"
```

E1M3 exposed several general limitations: accumulated stair rises were judged
against the starting height; inertia could carry the player into deep pits;
raised lifts were not treated as use targets; and redundant early-room states
could evict later discoveries. Exploration now handles those cases and seeks
nearby unpressed switch textures, useful health items and unowned keys. Switch
recognition uses the visible side's `SW1` texture family, with a local visibility
check. It does not read a line's special, tag or exit identifier.

Every fourth eligible archived restart also tries shortening a route longer
than 20 seconds. A replacement must reach the observed intermediate location
with its keys and weapons, at least the original health, and less elapsed game
time; an actual early completion is also accepted. Failed proposals preserve
the original prefix. This uses the same trajectory optimizer before a whole
level has been solved. The input-only blue-key regression demonstrates a
6,619-tic route shortened to under 30 seconds while retaining its key and
improving health. Exploration and post-completion optimization remain distinct.

A subsequent 49-proposal optimization pass reduced this learned completion to
**62.43 seconds / 2,185 tics**, below E1M3's own **120-second par**. It uses the
normal exit, finishes with **11 health, 69 armor and a shotgun**, and records
**32 kills**. Blocked movement is **0.29 seconds**; idle input is **12.00
seconds**. The optimizer first accepted three small input deletions, then a
trajectory-following proposal produced the faster run. It passed fresh route
replay and external-demo playback. This is the recommended demo to watch:

```sh
./gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/e1m3-optimized/winning.lmp"

# Reproduce shortening from the algorithm's completed input-only fixture.
./gradlew train -PtrainArgs="--wad reference-wads/DOOM.WAD --map E1M3 --improve src/test/resources/ai/e1m3-learned.lmp --output training/new-e1m3-optimized --target-seconds 120 --attempts 49 --minutes 1"
```

The movement policy is conservative around deep drops during exploration;
levels requiring deliberate drops may need further policy work. No global
route graph, E1M3 coordinates, special IDs, or engine-rule changes are supplied
to the learner. Generated demos, traces and logs remain under ignored `training/`.

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
favor shorter arrival times and higher health. When full, eviction first removes redundant entries from the most populated
position/key group, preserving distant discoveries over extra early-room variants.
Selection normalizes by group population and favors less-visited
locations and less-selected entries; five percent of selections start fresh.
Visit counts are separated by key ownership so returning after a pickup is useful.
Shared prefixes, archive entries, and spatial visit counts are bounded. The
active episode and best successful route have their own bounded storage.

After a first success, most attempts optimize it while occasional attempts
continue archive exploration. An explicit `--improve` run begins directly
with optimization. There is no neural network or exact-world snapshot system.

## Movement and wall handling

- A local swept **square** checks the player's footprint, respecting Doom's
  bounding-box collision, step-height and passage-height constraints. The probe
  accumulates legal stair rises along the predicted path. A clear
  center ray alone does not establish that the player fits past a corner.
- Exploration treats drops greater than 24 units as hazards. Its inertial
  prediction checks ordinary movement as well as strafing and brakes both axes
  before a ledge. Route optimization may still use drops present in a learned
  successful path.
- Novelty is scored at a **reachable endpoint**, using logarithmic visit penalties
  that remain useful after repeated visits. Unvisited
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
  Raised platforms can also be approached and activated even when the opening
  is tall enough: their floor still blocks walking. Moving lifts are observed
  until traversable. An inherited held Use input is released after restarting
  an archived prefix.
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
