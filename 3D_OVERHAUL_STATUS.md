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
