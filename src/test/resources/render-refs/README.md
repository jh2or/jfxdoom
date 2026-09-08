# Renderer reference checks

`VVideoReferenceImageTest` runs in the normal `test` task without a WAD. It
compares every pixel of a 320×200 framebuffer with
`authored-patch-composite.png`. Settings: palette indices mapped to RGB grayscale,
background index 42, no gamma correction, no scaling or aspect correction.

The source artwork is the explicit 5×7 matrix in `generate_patch_fixture.py`.
It was authored for this test, not captured from the Java renderer. The script
encodes the artwork as `authored.patch`, with origin offsets (2,3), disjoint
column posts and transparent holes. Independently, row-major composition of the
matrix produces the expected PNG: normal at (0,0), horizontally flipped at
(2,2), and flipped at (315,193). Opaque index zero, overlapping transparency,
unsigned indices and all screen edges are exercised. Patch interpretation follows
`patch_t`/`column_t` in `r_defs.h` and `V_DrawPatch`/`V_DrawPatchFlipped` in the
original `v_video.c`. Regenerate deliberately with:

```sh
python3 src/test/resources/render-refs/generate_patch_fixture.py
```

`TitlePageReferenceTest` also runs automatically. It independently reads the WAD
directory, TITLEPIC column posts and the first PLAYPAL palette directly from the
local file; it does not use production `WWad` or `Patch` for expected pixels.
It compares all 64,000 indices and RGB triples against production `dPageDrawer`
on both the first draw and a cached redraw over a different background. This
checks the actual Doom title asset without redistributing licensed WAD artwork.
Settings: 320×200, TITLEPIC at (0,0), PLAYPAL palette zero, no gamma/aspect scaling,
no gameplay ticks or RNG dependence. Use an original Doom IWAD with a complete
320×200 TITLEPIC. A missing WAD reports a JUnit skip.

```sh
bash ./gradlew test --max-workers=2
bash ./gradlew test --tests doom.system.TitlePageReferenceTest -Ddoom.wad=/absolute/path/DOOM.WAD
bash ./gradlew test --tests doom.ui.VVideoReferenceImageTest
```

The `doom.wad` property is forwarded to the title test and diagnostic probes.
Without it, those tests search reference-wads/{DOOM.WAD,doom.wad,doom1.wad,DOOM1.WAD}.
Older WAD tests retain their existing reference-wads lookup.

The existing `reference-screenshots/reference_e1m1.png` was introduced in commit
9719928 during investigation of distorted gameplay rendering. No exact IWAD
hash, source executable/version, player position/view angle, tic, palette or
render settings accompany it. It is retained for visual investigation and is
**not** treated as a verified vanilla gameplay reference. The automated checks
here establish patch and title rendering correctness, not full-world renderer
pixel equivalence. Establishing that stronger claim requires a reproducible
capture from a named reference engine and matched inputs.

All ten optional probes now report JUnit skips when disabled; these are
observational diagnostics, not reference assertions. Run each enabled probe in
its own invocation because it boots global engine state:

```sh
bash ./gradlew test --tests doom.system.E1M1ScreenshotProbe -De1m1.probe=1
bash ./gradlew test --tests doom.render3d.Fx3ScreenshotProbe -Dfx3.probe=1
```

The second command needs a working JavaFX display and 3D support; absent SCENE3D
support is a visible skip. An unavailable display remains a setup failure when
explicitly requesting the GPU probe. Failed palette/texture reads and a missing
spawned player now fail enabled diagnostics rather than silently returning.
