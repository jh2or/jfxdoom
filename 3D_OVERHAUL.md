# 3D Overhaul — JavaFX Scene-Graph Renderer (experimental)

Implementation plan for rendering the world view with JavaFX's intrinsic 3D
capabilities (`SubScene` + `PerspectiveCamera` + `TriangleMesh`/`MeshView` +
`PhongMaterial`) instead of displaying the software renderer's prerendered
frame. Progress is tracked in `3D_OVERHAUL_STATUS.md`.

## Ground rules

- **Opt-in, never a replacement.** The software renderer stays the default and
  the faithful path (CLAUDE.md definition of done is unchanged). The 3D
  renderer is selected per session via a Launchpad toggle or the `-fx3d` CLI
  flag.
- **Zero changes to `doom.render` and `doom.play`.** The new renderer only
  *reads* public engine state (`PSetup.*` level arrays, `GGame.players`,
  mobjs). Demo determinism must be untouched — verified by keeping all
  `./gradlew test` green and by the playsim never reading renderer state.
- **Floats only at the view boundary.** Map fixed_t → double is `v / 65536.0`;
  BAM angle → degrees. The playsim stays bit-exact fixed-point.
- All new code lives in package `doom.render3d` plus small wiring diffs in
  `DMain.dDisplay`, `JfxDoomApp`, `Launchpad`.

## Architecture

| Class | Responsibility |
|---|---|
| `Fx3Renderer` | Facade: owns `SubScene`, `PerspectiveCamera`, root `Group`; `active` flag; `renderPlayerView(Player)` mirrors `R_RenderPlayerView` (camera update + lazy level build/rebuild). |
| `Fx3SubsectorPolys` | Pure-math (headless-testable): reconstructs a **closed convex polygon per subsector** by clipping the level bbox down the BSP node planes (R_PointOnSide side convention), then by the subsector's own segs. Solves the "vanilla subsectors aren't closed" problem (the hard part of every hardware Doom renderer). |
| `Fx3LevelMesh` | Builds MeshViews from `PSetup`: wall quads from segs/sidedefs (mid for one-sided; upper/lower from front/back sector height deltas), floor/ceiling fans from the subsector polygons. Batches by material key. Skips sky flats/sky upper walls. |
| `Fx3Textures` (Phase 2) | Decodes column/post textures (`RData.rGetColumn`, `Patch`) and 64×64 flats through PLAYPAL into `WritableImage`s; caches per (texture, light-bucket) tinted variants; handles animated/switch texture swaps. |
| `Fx3Sprites` (Phase 4) | Billboard pool for mobjs; rotation-frame choice via the same `Spritedef`/`Spriteframe` tables `RThings` uses; per-frame distance sort for masked nodes (JavaFX has no alpha test → depth-test off + painter's order). |
| `Fx3Sky` (Phase 3) | Camera-locked textured cylinder standing in for sky flats. |

### Coordinate & camera mapping

- Doom map (x east, y north, z up, y-up right-handed) → JavaFX
  `(X, Y, Z) = (x, -z, y)` (JavaFX Y points down).
- Camera: `PerspectiveCamera(true)`, horizontal FOV 90°
  (`setVerticalFieldOfView(false)`), transforms = `Translate(x, -viewz, y)`
  then `Rotate(90° − angleDeg, Y_AXIS)`; BAM → degrees via
  `(angle & 0xFFFFFFFFL) * 360 / 2^32`. Near clip ~1, far clip 65536.
- Winding: front faces chosen so wall quads face their `frontsector` and
  floors face up / ceilings face down; a single global flip constant corrects
  the convention if the first visual check shows inside-out geometry.

### Shell integration (JfxDoomApp)

- Scene becomes `StackPane[ SubScene(3D, unmanaged), Canvas(2D UI) ]` — the
  existing PixelBuffer canvas stays on top and carries all 2D (status bar,
  border, HUD, menus, automap, wipe).
- In 3D mode the per-frame composite writes **transparent** pixels
  (`argb = 0`) wherever the view window is active and the UI byte is 0
  (the existing `startFrameHook` already clears the viewport slice of
  `screens[0]` to palette index 0 each frame), so the SubScene shows through
  exactly where the software 3D view used to be. `gc.clearRect` before
  `drawImage` in 3D mode.
- The SubScene is positioned/sized each frame to the active view window
  (`RDraw.viewwindowx/y`, `scaledviewwidth`, `viewheight` scaled from render
  pixels to canvas pixels), hidden when the view is inactive (automap, title,
  intermission…).
- `Platform.isSupported(ConditionalFeature.SCENE3D)` gate: if unsupported
  (e.g. Windows Sandbox software pipeline), warn and fall back to the software
  renderer — same degrade-gracefully precedent as audio.

### Engine integration (DMain)

`dDisplay`'s GS_LEVEL branch:

```java
if (Fx3Renderer.active) Fx3Renderer.renderPlayerView(p);
else                    RMain.rRenderPlayerView(p);
```

Level (re)build trigger: `Fx3Renderer` compares the `PSetup.sectors` array
reference against the one it built from — a new level allocates new arrays, so
no `PSetup` hook is needed.

## Known JavaFX limits and the chosen workarounds

| Limit | Workaround |
|---|---|
| No texture wrap mode (UVs clamp) | Bake small tile-repeats into images (walls: texture tiled up to ≥256px per axis; flats: 64×64 tiled 4×4 → 256×256) and split quads/polys on the corresponding map-unit grid so all UVs live in [0,1]. |
| No filter control (always bilinear) | Pre-upscale decoded textures ~4× nearest-neighbour so bilinear sampling stays crisp. |
| No shaders / fog → no distance light diminishing | Accept flat per-sector lighting: tint texture variants per light bucket; keep vanilla "fake contrast" (±1 light for axis-aligned walls). Documented fidelity loss. |
| No alpha test → transparent texels write depth | Masked mid-textures + sprites: `setDepthTest(DISABLE)` + manual back-to-front sort per frame. |
| No invert effect for invulnerability colormap | Approximate with `ColorAdjust` on the SubScene (documented fidelity loss). |
| Wipe needs pixels of the 3D view | `SubScene.snapshot()` once at wipe start. |

## Phases

1. **Static world proof-of-concept** — `Fx3SubsectorPolys` + `Fx3LevelMesh`
   (flat-colored by sector light, no textures) + `Fx3Renderer` + Launchpad
   toggle + DMain/JfxDoomApp wiring. Full mesh rebuild on sector-height
   change (naive but correct; Phase 3 makes it incremental). Unit test:
   polygon reconstruction against doom1.wad (every seg endpoint contained,
   positive areas, all E1M1–E1M9).
2. **Textures & lighting** — `Fx3Textures`, UV mapping incl. pegging
   (`ML_DONTPEGTOP`/`BOTTOM`), `textureoffset`/`rowoffset`, tile-bake + grid
   split, per-light-bucket tints, fake contrast.
3. **Dynamic world** — incremental sector height updates (per-tic point
   mutation instead of rebuild), animated flats/textures, switch textures,
   wall scrollers, sky cylinder.
4. **Sprites & masked geometry** — billboards, rotations, two-sided
   midtextures, distance sort, depth handling.
5. **Polish** — palette-flash overlay rectangle, wipe snapshot, weapon-sprite
   (psprite) overlay, fullscreen fit, `ColorAdjust` invulnerability
   approximation.

## Verification

- Phase 1: launch with 3D renderer, walk the E1M1 start room — geometry
  matches the software renderer's shapes (toggle comparison).
- Phase 3: open E1M1's first door, ride the secret-area lift — meshes track
  sector heights with no gaps.
- Phase 4: zombiemen billboards face the camera with correct rotation frames,
  no depth halos against walls.
- Every phase: `./gradlew test` stays green (no `doom.render`/`doom.play`
  source is modified); software-renderer mode plays a demo deterministically.
