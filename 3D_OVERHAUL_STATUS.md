# 3D Overhaul — Status

Tracks completed work against the plan in `3D_OVERHAUL.md`.
Legend: ✅ done · 🔄 in progress · ⬜ pending

## Phase 1 — Static world proof-of-concept ✅

- ✅ `Fx3SubsectorPolys` — closed convex polygon per subsector (BSP bbox clip + seg clip).
  Two vanilla-nodes precision hazards found & fixed: (1) split-fragment segs have
  builder-rounded endpoints that tilt the clip line — clip against the parent
  linedef's exact line instead; (2) NODES partition lines themselves are rounded
  16-bit data that can sit ~5 map units off the true line (E1M1 ss 156/159/160) —
  repaired by convex-hulling the clipped polygon with the subsector's seg endpoints.
  Genuine zero-area sliver subsectors (e.g. E1M6 ss 589) reconstruct as empty.
- ✅ `Fx3LevelMesh` — wall quads (mid/upper/lower) + floor/ceiling fans, flat-colored
  by sector light (32 buckets, fake contrast ±16), sky flats/upper-sky walls skipped,
  batched per (surface class, light bucket)
- ✅ `Fx3Renderer` — SubScene/camera facade, `renderPlayerView`, level rebuild on
  sector-array change + height-hash change (Phase 3 makes this incremental)
- ✅ Launchpad renderer toggle + `-fx3d` CLI flag
- ✅ `JfxDoomApp` wiring — SubScene behind canvas, transparent viewport cutout,
  per-frame position/visibility sync, SCENE3D support gate
- ✅ `DMain.dDisplay` branch (3D vs software view)
- ✅ Unit test `Fx3SubsectorPolysTest` vs doom1.wad (E1M1–E1M9) — green
- ✅ Verified: full `./gradlew test` green; `Fx3ScreenshotProbe` (-Dfx3.probe=1)
  snapshot matches `Fx3SoftwareViewProbe` structurally (orientation, no mirroring,
  winding correct, 64° vertical FOV matches this port's square-pixel projection);
  live `-fx3d` run shows 3D view + opaque status-bar composite

## Phase 2 — Textures & lighting ✅

- ✅ `Fx3Textures` — composites column/post textures (R_GenerateComposite
  equivalent) and 64×64 flats, shades through COLORMAP (authentic non-linear
  palette shading, cm = (15−lightnum)×2−2), resolves via PLAYPAL → `WritableImage`
- ✅ Tile-bake + grid split so all UVs ∈ [0,1] (no wrap mode in JavaFX):
  per-texture pitch = size × tiles covering ≥128 map units; walls split on
  that grid, flats on the world-aligned 128-unit grid
- ✅ Nearest-neighbour ×2 pre-upscale for crisper bilinear sampling
- ✅ Wall UVs: pegging (`ML_DONTPEGTOP`/`BOTTOM` per r_segs.c, zTexTop form),
  `textureoffset`/`rowoffset`, `seg.offset`
- ✅ Flat UVs: 64-aligned world mapping (u = x, v = −y)
- ✅ Material cache per (kind, base index, lightnum) + fake contrast ±1 lightnum
- ✅ Bonus (pulled forward from Phase 3): animated flats/walls + switches work
  via `refreshTranslations()` — materials re-resolve
  `RData.flattranslation`/`texturetranslation` each tic and swap diffuse maps
- ✅ Verified: probe snapshots N/W/S/E at E1M1 start vs software render —
  texture choice, alignment, flat phase, light tone all match; suite green

## Phase 3 — Dynamic world ✅

- ✅ Split static/dynamic rebuild instead of point mutation (simpler, handles
  topology changes from moving heights): sectors join the dynamic set the
  first time their state (heights, lightlevel, floor/ceiling pic) deviates
  from the load-time baseline; the small dynamic group rebuilds per state
  change, the static group only when a new sector starts moving or a switch
  texture flips (side-texture hash)
- ✅ Animated flats/textures via translation-table map swapping (Phase 2);
  switch textures via full-rebuild trigger; blinking lights via the dynamic
  set (lightlevel deviation)
- ✅ Wall scrollers (line special 48): scroller segs live in the dynamic
  group, textureoffset in the dynamic hash
- ✅ Sky cylinder (`Fx3Sky`): 8×45° camera-locked segments, 2×-tiled
  fullbright SKY texture, vanilla proportions (row 100 on the horizon,
  4 repeats per turn); sky-flat/sky-wall holes show through
- ✅ Verified: probe drives a real DR door via the playsim (mid-swing +
  fully open — geometry tracks, pegging correct), sky + animated nukage
  courtyard render vanilla-like; suite green

## Phase 4 — Sprites & masked geometry ✅

- ✅ `Fx3Sprites` — one billboard MeshView per live mobj (identity-pooled,
  mark-and-swept via `Sector.thinglist`), rotation frames via
  `Spritedef`/`Spriteframe` with R_ProjectSprite's rotation formula, flip
  baked into the cached image, vanilla offsets (−spriteoffset /
  +spritetopoffset), fullbright frames, MF_SHADOW ≈ node opacity 0.35
- ✅ Two-sided midtextures: per-facing masked quads (alpha image, tiled
  horizontally only, single vertical run with r_segs texturemid rule),
  CullFace.BACK, emitted by the static/dynamic passes
- ✅ Depth handling — better than planned: depth test stays ON; JavaFX
  renders in scene-graph order, so a sorted group after the opaque world
  gets wall occlusion from the depth buffer for free, and the per-frame
  back-to-front sort (masked + sprites together) handles alpha-writes-depth
  between translucent nodes. No `DepthTest.DISABLE` needed.
- ✅ Verified: dead marine / barrels / armor render at the start; zombiemen
  visible through the opened door, occluded correctly by the door frame;
  suite green

## Phase 5 — Polish ✅

- ✅ Palette-flash overlay: additive Rectangle between SubScene and canvas,
  colored from the active sub-palette's entry 0 (the pure tint component) —
  damage red / bonus gold / radsuit green track `setPaletteHook`
- ✅ Wipe: `Fx3Renderer.captureViewToScreens0()` snapshots the SubScene at
  wipe start and writes it into screens[0]'s view window as palette indices
  (15-bit RGB → PLAYPAL lookup); the melt then peels real 3D pixels away
  over the live view
- ✅ Weapon sprite: the software psprite pipeline draws just the weapon into
  the otherwise-unused viewBuffer (`rSetupFrame` + `rDrawPlayerSprites`);
  the shell composites non-zero view pixels over the SubScene — pixel-exact
  vanilla weapon at render scale
- ✅ Invulnerability/light-amp: `ColorAdjust` pass on the SubScene
  (desaturate+brighten / brighten) — documented approximation
- ✅ Final: full `./gradlew test` green; live playthrough with weapon,
  firing, sprites, HUD verified

## Side-by-side mode (3D_SIDEBYSIDE_PLAN.md) ✅

- ✅ Launchpad renderer ComboBox: Software / JavaFX 3D / Side-by-side
  (horizontal) / Stacked (vertical); CLI `-sbs` / `-sbsv`; render-resolution
  combo enabled in side-by-side (applies to the software pane), greyed only
  for pure 3D
- ✅ One window, two equal panes, one playsim — both renderers read the same
  tic (`DMain.dDisplay` runs software first, then the scene graph)
- ✅ Buffer conflict solved: the fx3 weapon pass draws into its own
  `weaponPass1/2` buffers via a `VVideo.viewBuffer` reference swap (RDraw
  re-reads the field per call) — `viewBuffer` belongs to the software pane
  again; the borrow-hack in pure 3D mode is gone too
- ✅ Dual composite in the shell (`BlitSurface` per pane: software classic /
  fx3 cutout), dual fullscreen aspect fitter, per-pane SubScene tracking
- ✅ Wipe compromise as approved: shared screens[0] melts like software mode
  on both panes (`captureViewToScreens0` disabled in side-by-side)
- ✅ Verified: live `-sbs` run — same tic both panes (weapon, corpse,
  barrels, HUD identical), suite green

## Outcome

All five phases complete. The experimental renderer is selectable in the
Launchpad ("JavaFX 3D renderer (experimental)") or via `-fx3d`; the faithful
software renderer remains the default. `doom.render` and `doom.play` were
never modified. Known accepted deviations: no per-pixel distance light
diminishing, bilinear texel softness, approximated fuzz/invulnerability,
no extralight (muzzle flash brightening).


## Isometric mode (`feat/isometric-renderer`)

Implemented as an opt-in presentation of the existing JavaFX renderer. Select
**Isometric (experimental)** or pass `-iso`. Implementation plan:
[`ISOMETRIC_RENDERER_PLAN.md`](ISOMETRIC_RENDERER_PLAN.md).

### Implemented

- Fixed 45-degree azimuth / 35.264-degree elevation orthographic projection,
  640-map-unit viewport width, actor-position/elevation tracking, and resolution
  independent framing. JavaFX 21's parallel projection uses a viewport-sized
  depth range irrespective of farClip; only depth is compressed to accommodate
  the map range. The X/Y projection remains true orthographic.
- Ceiling and sky-cylinder omission; original floor polygons/materials, moving
  sectors, switches, and animated textures are shared with the perspective view.
- Per-seg opaque wall surfaces retain their identity and have prebuilt full/short
  variants. Foreground intersection with a protected player region chooses the
  variant. Retained barriers are at most 24 units above their front floor, with
  thin caps; high upper fragments disappear. Closed door surfaces retain a sill
  while they cover the opening. Selected masked fragments are hidden.
- Twelve-unit cutaway hysteresis persists across dynamic mesh rebuilds, keyed by
  seg and surface kind. A new level, player replacement, or position discontinuity
  clears the selection history. Camera motion does not rebuild level meshes.
- Visible player sprite and gold facing marker; no first-person weapon pass,
  including in wipe capture. Sprites stay upright to preserve floor contact, with
  camera-direction-based frame selection and elevation-aware transparency order.
- Existing keyboard controls, simulation, AI, collision, shooting, save format,
  and demo commands retained. No changes to `doom.play` or `doom.render`.
- Existing perspective/software/comparison rendering paths retained. UI continues
  through the shared shell. Isometric mode uses the existing SCENE3D fallback.

### Verification

- `./gradlew test`: **1,007 passed, 11 skipped, 0 failed**. The skips are disabled
  diagnostics/integration probes; no missing-WAD skip occurred in this run. The
  isometric integration probe passed separately for both WADs.
- Seven headless tests cover projection signs/foreshortening, depth invariance,
  sprite frame boundaries, foreground/background/raised-floor cutaways, hysteresis,
  and retained door geometry.
- `Fx3IsoIntegrationTest` runs separately with `-Diso.probe=1` and a supported 3D
  pipeline. It has been exercised with the local Doom and Doom II WADs. Each run
  compares player/actor state, sectors, and gameplay RNG for 210 demo tics with
  and without isometric rendering. This proves equality for those sampled runs,
  not universal vanilla compatibility.
- An E1M1 fixed route completes via the normal exit using the existing keyboard
  command builder (turn/forward/use, monsters disabled). It does not relocate the
  actor, enable noclip, or directly activate doors or the exit. Combat/actor
  activity is covered separately by the recorded demos.
- The integration check also exercises actual shell CLI selection, viewport
  centering/resizing, player inclusion, no weapon overlay, door motion, cutaway
  state across rebuilt geometry, perspective reset, level changes, save/load,
  death, fresh-level respawn, HUD, automap, menus, reduced viewport, damage tint,
  and intermission/wipes. Screenshots were visually inspected at representative
  corridor, walkway, exit-room, and busy-map positions.
- Reproducible screenshots, WAD SHA-256 identities, traversal result, and benchmark
  files: `build/probe/isometric/<WAD filename>/`. These are generated artifacts,
  not source-controlled WAD art. Commands are in `README.md`.
- Save/load coverage exposed an existing padded-LINEDEFS validation issue on maps
  with odd linedef counts (including E1M2). `SaveGame.load` now trims its topology
  input to the actual lump length. A dedicated save regression test covers this;
  the serialized format and map fingerprint are unchanged.

### Limits and remaining platform coverage

- Ceiling removal exposes nearby rooms, secrets, and actors; no exploration mask
  is applied. Rendering does not grant actors new sight or movement abilities.
- Cutaways operate on complete seg surfaces. Long or unusual walls may disappear
  abruptly; very close barriers can still cover the player's feet. Caps use a
  narrow strip of the existing wall material rather than new authored art.
- Upright Doom artwork is foreshortened. Existing alpha/depth approximations remain
  for intersecting sprites/fences; existing JavaFX lighting and effect differences
  also remain. This is an experimental view, not a new art pipeline.
- Camera rotation, interactive zoom, and isometric comparison panes remain deferred.
- Validation uses Linux JavaFX ES2 in the container. Windows D3D and interactive
  fullscreen behavior on physical monitors have not been exercised here. The
  checks above do not substitute for broad arbitrary-PWAD testing.
- Performance samples distinguish cold scene/material upload from warmed frames.
  They include synchronous screenshot readback, so they are not an FPS guarantee.
  Isometric wall variants increase scene-node count and allocations relative to
  the batched perspective renderer. Measurements are summarized below.


### Performance sample (2026-09-13)

Linux container, JDK 21.0.8, JavaFX 21.0.4 ES2, display `:1`,
`-Dprism.forceGPU=true`; viewport 640x400. Each sample follows a cold frame and
10 warmup frames with 70 simulation/update/synchronous-snapshot frames. Times
include snapshot readback where indicated, but exclude PNG encoding. Heap
allocation includes simulation, scene updates, and snapshot image creation.

| Map | View | Update median (ms) | Snapshot median / p95 (ms) | Nodes | Heap MB/frame |
| --- | --- | ---: | ---: | ---: | ---: |
| E1M1 | perspective | 0.20 | 4.25 / 22.32 | 269 | 1.63 |
| E1M1 | iso | 0.20 | 3.90 / 22.57 | 1250 | 2.68 |
| E1M6 | perspective | 0.33 | 5.39 / 61.58 | 641 | 2.34 |
| E1M6 | iso | 0.47 | 6.06 / 38.54 | 3303 | 4.93 |
| MAP01 | perspective | 0.10 | 4.80 / 8.07 | 218 | 1.06 |
| MAP01 | iso | 0.08 | 3.87 / 9.42 | 827 | 1.05 |
| MAP16 | perspective | 0.17 | 4.36 / 16.93 | 445 | 1.19 |
| MAP16 | iso | 0.15 | 5.13 / 12.74 | 2079 | 1.22 |

Cold scene/material initialization and first rasterization took approximately
1.1–2.4 seconds in these samples, including the existing perspective renderer.
E1M6 showed occasional readback/frame-time spikes in both modes; this short
synchronous test does not establish stable real-time FPS. The isometric CPU update
medians remained below 0.5 ms. No additional production culling was introduced
based on these samples; allocation and node growth remain the main scaling risks.
