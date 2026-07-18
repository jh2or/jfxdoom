# Side-by-Side Renderer Mode — Plan

Run one game with BOTH renderers visible at once: one pane shows the faithful
software renderer, the other the experimental JavaFX scene-graph renderer.
Companion to `3D_OVERHAUL.md`; status will be tracked in
`3D_OVERHAUL_STATUS.md` once implementation starts.

## Why this is cheap in this architecture

There is exactly **one playsim**, and both renderers are pure *readers* of it
(`PSetup.*`, the player mobj). Rendering the same tic twice cannot desync
anything — the panes are a true A/B of identical game state, every frame.
No engine (`doom.render`, `doom.play`) code changes at all; the work is in
`DMain.dDisplay` (call both renderers), `doom.render3d` (buffer ownership),
and the JavaFX shell (two panes).

## Decision: one window, two panes (not two windows)

Two OS windows would split keyboard focus (you would play in one and the
other would be a passive mirror at best) and make equal-size guarantees
fiddly. A single window with two equal panes gives one Scene, one input
path, one AnimationTimer — nothing about input/timing changes. Both panes
are **always the same pixel size** (the "same resolution" requirement).

The Launchpad renderer control becomes a ComboBox:

| Entry | Layout |
|---|---|
| Software (faithful) — default | as today |
| JavaFX 3D (experimental) | as today |
| Side-by-side (software ▏3D) | `HBox`: software left, 3D right |
| Stacked (software ▲ 3D) | `VBox`: software top, 3D bottom |

CLI: `-fx3d` (unchanged) and new `-sbs` / `-sbsv` for the two layouts.
Windowed sizing: each pane is 320N×200N, so the window is 640N×200N
(horizontal) or 320N×400N (stacked) — the user picks a scale that fits
their screen. Fullscreen letterboxes the *pair* (aspect 3.2:1 resp. 0.8:1).

## Internal render resolution (per your requirement)

In side-by-side mode the combo box is **enabled** and applies to the
software pane exactly as in pure software mode (`viewBuffer` at
RENDER_MUL). The 3D pane's world always renders at display resolution.
Only pure "JavaFX 3D" mode keeps the combo greyed out. Side benefit: the
3D pane's weapon overlay (drawn by the software psprite pipeline) renders
at the selected internal resolution too.

## Buffer ownership — the one real conflict

Today the fx3 weapon overlay borrows `VVideo.viewBuffer` (unused in pure 3D
mode). In side-by-side mode the software renderer needs it for its full
frame. Fix: give the fx3 weapon pass its **own pair of buffers**
(`weaponPass1/weaponPass2` in `Fx3Renderer`, RENDER-sized). `RDraw` reads
`VVideo.viewBuffer` afresh on every draw call, so the psprite pass can swap
the reference in, draw, and restore — no `doom.render` changes:

```
saved = VVideo.viewBuffer
VVideo.viewBuffer = weaponPass2   → fill 1, draw psprites
   (copy to weaponPass1 → fill 0, draw psprites — two-pass mask as today)
VVideo.viewBuffer = saved
```

This is done in ALL fx3 modes (pure 3D included), which also removes the
current borrow-the-viewBuffer hack. The shell's weapon composite reads
`weaponPass1/2` instead of `viewBuffer`.

## Per-frame flow (side-by-side, GS_LEVEL)

1. `DMain.dDisplay`: `RMain.rRenderPlayerView(p)` (fills `viewBuffer`),
   then `Fx3Renderer.renderPlayerView(p)` (scene graph + weapon buffers).
2. `finishUpdateHook` composites **two** ARGB buffers:
   - left/top pane: today's software composite (screens[0] UI over
     `viewBuffer`) — code path unchanged;
   - right/bottom pane: today's fx3 composite (screens[0] UI opaque,
     weapon-mask pixels, transparent holes over the SubScene).
3. SubScene + palette-tint rectangle are positioned inside the right pane's
   view window; menu/status bar/HUD/automap appear on both panes
   automatically since both composites read the same `screens[0]`.

Shell refactor: extract a small `Pane` helper in `JfxDoomApp` (canvas +
argb + PixelBuffer + image + composite variant) used once or twice.
`RDraw.viewwindow*` (screen-size +/-) is shared state, so view-size changes
apply to both panes symmetrically.

## Wipe (screen melt) compromise

The melt operates on the shared 320×200 `screens[0]`, which cannot hold two
different view images at once. In side-by-side mode the fx3 wipe injection
(`captureViewToScreens0`) is disabled and the melt behaves exactly like
today's pure-software mode on both panes. Pane-specific melts would need
per-pane wipe buffers — out of scope (noted as a possible follow-up).

## Performance

Software render + scene-graph render + two composites, at 35 Hz. Each half
is what the two existing modes already pay individually; a desktop that
runs both modes fine will run the pair fine. RENDER_MUL still scales the
software half's cost as before.

## Implementation steps

1. `Launchpad`: renderer ComboBox (4 entries, replaces the checkbox),
   render-resolution greying only for pure 3D, prefill/callback enum
   `RendererMode` instead of the boolean; CLI parsing in `JfxDoomApp`.
2. `Fx3Renderer`: own weapon buffers + viewBuffer swap in the psprite
   pass; `sideBySide` flag; skip `captureViewToScreens0` when set.
3. `DMain.dDisplay`: three-way branch (software / fx3 / both).
4. `JfxDoomApp`: pane extraction, HBox/VBox layouts, dual composite in
   `finishUpdateHook`, SubScene/tint positioning relative to the 3D pane,
   fullscreen aspect fitter for the pair.
5. Verification: full `./gradlew test` (must stay green — no engine
   changes); live run `-sbs`: fire pistol (same tic in both panes), open
   the first door (moves in both), toggle automap/menu (drawn on both),
   +/- screen size (both view windows shrink), damage flash (left via
   palette, right via tint rect).

## Open points (answer or accept defaults)

- Orientation entries as proposed (horizontal + stacked)? Default:
  implement both — it's one container-class switch.
- Pane labels ("software" / "JavaFX 3D") overlaid in a corner? Default: no
  labels, clean panes.
- Wipe compromise above acceptable? Default: yes.
