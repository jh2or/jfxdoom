# Isometric renderer implementation plan

Status: implemented on `feat/isometric-renderer`. Validation and measured
limitations are recorded in `3D_OVERHAUL_STATUS.md` under "Isometric mode".
The phases below retain the design and acceptance criteria for reference.

## Objective and scope

Add an opt-in isometric view of the existing Doom game using the JavaFX 3D
renderer. Keep Doom's maps, gameplay, controls, physics, AI, weapons, interactions,
audio, save format, demo commands, and 35 Hz simulation.

The references to Diablo and Baldur's Gate describe the desired perspective
only. RPG mechanics, click-to-move, pathfinding, mouse aiming, and screen-relative
movement are outside this implementation.

The software renderer remains the default. Existing perspective and comparison
modes retain their behavior. Work belongs in `doom.render3d` and its UI integration;
do not modify `doom.play`, `doom.render`, or the reference C implementation for
this feature. Follow the renderer boundary in `CLAUDE.md` section 9a.

## Initial presentation decisions

- Add a Launchpad option, `Isometric (experimental)`, and a CLI flag, `-iso`.
- Use true orthographic projection, a fixed map-relative azimuth of 45 degrees,
  and an elevation of approximately 35.264 degrees above the horizontal.
- Follow the viewing player's world position, independent of facing and view bob.
  Track actor elevation so lifts and falling remain framed. Use direct tracking
  initially; camera smoothing is deferred.
- Define framing in world units, independent of display resolution. Start with
  640 map units across the viewport and tune against real maps before release.
- Keep the existing keyboard controls. Turning changes the visible player's
  facing, not the camera direction.
- Render the player's world sprite and omit the first-person weapon overlay.
- Omit ceilings; cut away foreground geometry where needed to see the player.
- Show exposed map geometry and actors without adding fog of war or room-discovery
  rules. This changes information available to the human player; it must not
  change monster sight, activation, collision, or shooting.
- Use the existing Doom artwork. Document billboard limitations rather than
  making new assets a prerequisite.
- Keep the fixed camera direction and framing for the first release. Interactive
  zoom, camera rotation, and an isometric comparison-pane option are follow-ups.

## Existing implementation and intended changes

| File | Role in this feature |
| --- | --- |
| `src/main/java/doom/render3d/Fx3Renderer.java` | Share scene lifecycle, geometry updates, textures, and effects; select perspective or isometric presentation. |
| `src/main/java/doom/render3d/Fx3LevelMesh.java` | Preserve surface identity for cutaways; omit ceilings and build any needed wall caps. |
| `src/main/java/doom/render3d/Fx3SubsectorPolys.java` | Reuse existing reconstructed floor polygons. Change only for demonstrated geometry defects in separate fixes. |
| `src/main/java/doom/render3d/Fx3Sprites.java` | Include the player, choose orthographic directional frames, orient billboards, and expose depth information. |
| `src/main/java/doom/render3d/Fx3Sky.java` | Keep the existing camera-locked sky for perspective mode; omit it from the isometric scene. |
| `src/main/java/doom/ui/Launchpad.java` | Add renderer selection and retain the existing internal-resolution enablement rules. |
| `src/main/java/doom/ui/JfxDoomApp.java` | Parse the flag, configure the mode after reset, handle viewport sizing and overlays, and retain the SCENE3D fallback. |
| `src/main/java/doom/system/DMain.java` | Reuse the existing JavaFX dispatch; make only integration changes demonstrated necessary. |

Prefer a small presentation enum and concrete camera/visibility helpers within
`doom.render3d`. Do not create another copy of the renderer or a general engine
abstraction. Proposed helper names are `Fx3IsoCamera` and `Fx3IsoVisibility`.
Pure projection and visibility calculations should be testable without JavaFX.

## Phase 1 — Projection and mode wiring

1. Add the isometric presentation setting and Launchpad/CLI selection. Preserve
   existing flag precedence and renderer fallback behavior.
2. Implement a `ParallelCamera` with an explicit world-to-view transform. JavaFX
   uses pixel coordinates for this projection: apply viewport centering and
   uniform scale deliberately rather than copying perspective-camera placement.
3. Express camera right, up, and forward vectors explicitly using the existing
   Doom `(x, y, z)` to JavaFX `(x, -z, y)` conversion. Reuse these vectors for
   projection, sprite orientation, depth, and visibility decisions.
4. Derive scale from the actual world viewport width, excluding HUD and borders.
   Set clipping bounds so normal actor elevations and map positions stay visible.
5. Follow `player.mo` position/elevation. Do not use `player.viewz` for camera bob
   or `player.mo.angle` for camera rotation.
6. Omit the perspective sky cylinder and use a neutral background for map voids.
7. Make reset and subsequent session boot restore the selected camera correctly.

Acceptance:

- A synthetic coordinate-axis scene has equal axis foreshortening and parallel
  projected lines. Object scale does not change with depth.
- Turning the player does not rotate the map; walking and lifts move the camera
  anchor as specified.
- Window scale and fullscreen preserve world framing and centering.
- Software, perspective, and existing comparison modes still boot correctly.

This phase is a projection milestone, not yet a playable indoor renderer.

## Phase 2 — Floors and visible actors

1. Add an isometric mesh-build setting that omits ceiling surfaces without
   changing sector state. Retain floors, steps, walls, moving sectors, scrolling
   surfaces, switch textures, and texture animation.
2. Include the viewing player's mobj in sprite enumeration for isometric mode.
   Keep the perspective exclusion intact.
3. Choose directional frames from the common orthographic viewing direction,
   preserving the existing frame-index convention and horizontal flip behavior.
4. Start with vertical, yaw-facing billboards and the existing foot anchoring.
   Compare elevated camera-facing billboards on the same fixture. Select and
   document the approach that best preserves ground contact and recognizable art;
   camera-facing quads must not visibly penetrate floors.
5. Add a small render-only ground marker under the viewing player to aid location
   and facing readability. Position it from the actor's supporting floor, not a
   global height plane; give it a small depth offset to avoid z-fighting.
6. Skip weapon-pass generation and clear its buffers in isometric mode. Ensure
   both `compositeFx3` and wipe capture treat those passes as absent.
7. Sort isometric sprites and masked surfaces by camera-space depth, including
   height. Preserve the existing perspective ordering initially. Supply masked
   surfaces with a 3D center instead of only a map-space midpoint where required.

Acceptance:

- Player movement, turning, firing, death, and respawn use the correct world
  animation state; weapon sprites never appear over the isometric view.
- Directional sprite frames do not change merely because an actor translates
  sideways across an orthographic view.
- Corpses, pickups, projectiles, flying enemies, and ceiling-hanging decorations
  have sensible height and grounding.
- Overlapping sprites and fences are exercised at different elevations. Treat
  midpoint sorting as an approximation: split problematic masked geometry if a
  fixture demonstrates an ordering defect; do not promise perfect transparency.

## Phase 3 — Foreground wall cutaways

Implement a deterministic geometric cutaway, without introducing room semantics.
Sector identity alone is insufficient because one sector can cover disconnected
areas and a single material batch can span several sectors.

1. Preserve linedef/seg, adjacent-sector, surface-kind, and bounds information for
   wall render units. Separate independently cuttable geometry from large material
   batches; retain batching for unaffected floors and walls where practical.
2. Define a protected screen region around the player's projected body and feet.
   Identify walls whose projected bounds overlap that region and whose geometry
   lies between the camera and the protected player volume. Use geometric depth
   checks after the broad-phase bounds test to avoid removing background walls.
3. For obstructing opaque wall units, show a shortened representation anchored to
   the relevant floor. Start with a 24-map-unit retained wall height, clamped to
   the original surface interval. Hide upper fragments that cannot retain a
   meaningful base. Add visible caps/back faces only where the cutaway exposes
   them, using existing materials with a documented simple cap treatment.
4. Preserve a readable closed/open door cue. A cutaway door must not look like a
   traversable opening while it is physically closed. Keep enough lower geometry
   or a render-only threshold indicator to distinguish its state.
5. Handle obstructing masked surfaces explicitly: initially hide the selected
   fragment using the same protected-region policy. Avoid translucent wall fading
   in this version because of the renderer's existing alpha/depth limitations.
6. Prebuild intact/cut variants for static wall units where practical. Change
   visibility on camera movement rather than rebuilding the whole level. Rebuild
   affected variants when doors, lifts, or neighboring sector heights change.
7. Add modest entry/exit hysteresis to prevent wall visibility flicker at region
   boundaries. Clear that state on level changes and camera discontinuities.

Acceptance:

- The player and immediate supporting floor are readable in a narrow corridor,
  behind a tall foreground wall, and beside a closed door.
- A background wall does not disappear just because its bounds overlap the player.
- Cut geometry respects steps, pits, moving floors, and adjacent-sector heights.
- Hidden/shortened walls still block movement, bullets, and monster sight exactly
  as before; closed doors still require the normal use action.
- Camera tracking alone does not trigger complete level mesh rebuilds.

The protected-region algorithm and retained height are initial design choices.
Tune them using the fixtures below; record any map layouts that remain ambiguous.
Do not expand this phase into fog of war or automatic room discovery.

## Phase 4 — Shell behavior and lifecycle

1. Verify HUD, status bar, messages, menus, automap, palette tints, powerup effects,
   intermission, finale, and screen wipes over the new view.
2. Exercise reduced view windows, fullscreen, and display-resolution changes.
3. Confirm scene state and weapon buffers cannot leak between perspective and
   isometric sessions when returning through the Launchpad.
4. Handle initial boot, title/demo playback, new level, save/load, teleport,
   death, and respawn. Follow the currently viewed player and reset stale cutaway
   selections after position discontinuities.
5. Retain the existing unsupported-SCENE3D fallback and communicate it through the
   current shell mechanism. An unsupported pipeline is not visual validation.

Acceptance:

- All existing non-world screens work; no stale weapon overlay or map geometry
  survives a session change.
- Save/load and demo playback need no new persistent simulation fields.
- Existing renderer modes and comparison-pane compositing remain functional.

## Phase 5 — Verification, performance, and documentation

### Automated coverage

- Projection: known basis vectors, orthographic scale invariance, centering, and
  Doom/JavaFX coordinate signs at multiple viewport sizes and actor heights.
- Sprites: directional frame boundaries, inclusion of the viewing player, and
  elevation-sensitive depth order.
- Visibility: foreground versus background geometry, player-region intersections,
  stepped surfaces, closed doors, and hysteresis/reset behavior.
- Integration: renderer mode selection, lifecycle reset, and weapon-free overlay
  handling where existing test seams permit headless verification.
- Simulation invariance: feed the same recorded or scripted tic commands through
  fresh equivalent sessions with and without isometric rendering. Compare per-tic
  player/actor state and gameplay RNG state. Compare simulation data rather than
  renderer scratch globals. Record fixture and WAD identity for repeatability.

Run focused tests while implementing each phase, then `./gradlew test` for final
regression coverage. Report missing-WAD skips separately from successful checks.
If renderer parity needs JavaFX, run that check with a real SCENE3D-capable pipeline;
a headless simulation test alone cannot prove renderer non-interference.

### Visual fixtures and playthrough

Use a small synthetic map/geometry fixture for controlled cases, then real WADs:

| Scenario | What to inspect |
| --- | --- |
| Indoor corridor and tall foreground wall | Ceiling removal, player visibility, cutaway caps, flicker. |
| Stairs, pit, raised platform, and lift | Camera tracking, actor grounding, wall bases, depth ordering. |
| Closed/opening/closing door and switch | State readability, dynamic geometry, normal use and blocking. |
| Fence with overlapping sprites | Alpha ordering and masked-surface cutaways. |
| Player death, corpses, airborne enemies/projectiles | Animation, height, and camera anchor. |
| Outdoor sky sector and map boundary | Background treatment and exposed missing geometry. |
| Teleport and level transition | Stale visibility state and scene lifecycle. |

Complete an E1M1 playthrough with original controls, plus representative Doom II
maps if a local WAD is available. Capture named viewpoints using fixed commands,
viewport settings, and WAD identity. Visual approval concerns the isometric
presentation; it is not a pixel-equivalence comparison to first-person Doom.

### Performance

Measure frame time, mesh rebuild count, scene-node count, and allocations for a
small map and a busy/large map on the same supported machine. Compare with the
existing JavaFX renderer. Investigate visible stutter or sustained failure to
service the 35 Hz simulation. Add view-volume culling or coarser grouping only
where measurements identify a need. Store measurements and hardware/pipeline
details with the implementation status.

### Documentation and completion

Update `README.md` with `-iso`, renderer selection, unchanged controls, and visual
limitations. Add an isometric section to `3D_OVERHAUL_STATUS.md` that distinguishes
implemented, tested, and still-limited behavior. Keep this plan's checklists or
phase statuses current as work lands.

## Delivery order and estimates

Implement sequentially: projection/mode wiring, actors/open ceilings, wall
cutaways, lifecycle integration, then final validation. Keep each milestone
reviewable and retain passing checks for existing renderers throughout.

| Milestone | Rough engineering effort |
| --- | --- |
| Phases 1–2: first useful visual prototype | 3–6 working days |
| Phase 3: usable cutaways on representative maps | 5–10 working days |
| Phases 4–5: integration, verification, and documentation | 3–6 working days |

Estimates assume one developer familiar with the repository and access to a
working JavaFX 3D environment. Unusual WAD geometry and transparent-surface
ordering are the primary uncertainty; broad arbitrary-PWAD polish may take longer.

The feature is complete when it is selectable, a representative level is playable
start to finish from the isometric view with existing controls, the player remains
readable in the tested indoor cases, dynamic geometry and overlays work, and
simulation/regression checks pass with limitations recorded.
