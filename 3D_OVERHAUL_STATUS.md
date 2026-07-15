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

## Phase 3 — Dynamic world

- ⬜ Incremental sector floor/ceiling height updates (point mutation, no rebuild)
- ⬜ Animated flats/textures (`PSpec` anim tables), switch textures
- ⬜ Wall scrollers (line special 48)
- ⬜ Sky cylinder (`RSky.skytexture`), sky-flat holes

## Phase 4 — Sprites & masked geometry

- ⬜ `Fx3Sprites` — mobj billboards, rotation frames via `Spritedef`/`Spriteframe`
- ⬜ Two-sided midtextures (masked quads)
- ⬜ Back-to-front distance sort + `DepthTest.DISABLE` for masked nodes

## Phase 5 — Polish

- ⬜ Palette-flash overlay (damage/bonus/radsuit tints via `setPaletteHook`)
- ⬜ Wipe: `SubScene.snapshot()` at wipe start
- ⬜ Weapon sprite (psprite) overlay at view scale
- ⬜ Invulnerability `ColorAdjust` approximation
- ⬜ Final: full `./gradlew test` run + software-mode demo determinism check
