# Doom Java/JavaFX Port — Agent Instructions

You are working on a port of the original `linuxdoom-1.10` C source to Java 21 with JavaFX as the audiovisual layer. The goal is a faithful, working port that runs original Doom WAD files. Faithful translation beats "improved" code every time.

This document is read on every session. Follow it precisely.

---

## 1. Project Layout

```
jfxdoom/
├── reference/linuxdoom-1.10/   # read-only C source (DO NOT modify)
├── src/main/java/doom/
│   ├── system/     platform, main loop, timing
│   ├── wad/        WAD loading
│   ├── fixed/      fixed-point math
│   ├── render/     software renderer
│   ├── render3d/   experimental JavaFX scene-graph renderer (opt-in; see §9)
│   ├── play/       game logic, physics, thinkers
│   ├── sound/      audio
│   └── ui/         JavaFX shell (last to be built)
├── src/test/java/doom/          # one test class per C file translated
├── reference-wads/doom1.wad     # shareware WAD for testing
├── CLAUDE.md                    # this file
└── PORT_STATUS.md               # module tracking dashboard
```

The `reference/` directory is the source of truth for behavior. Never edit it. Always read from it.

---

## 2. Translation Philosophy

**Faithful first, idiomatic never.** Preserve the structure, names, and bugs of the original C. Do not "improve," "modernize," or "refactor" while translating. Doom's C has three decades of correctness baked in. Reproduce it.

Specifically:

- Keep function names readable as their C originals. `P_SetupLevel` → `PSetup.setupLevel` (class = file prefix, method = camelCase of the rest).
- Preserve globals as `public static` fields on a class named after the C file. Example: `r_main.c`'s globals live on `RMain` as `public static` fields.
- Keep control flow identical. If the C uses a `goto`, use a labeled `break`/`continue` or a boolean flag — do not restructure the logic.
- Preserve comments from the C source verbatim. Add new comments only to explain Java-specific translation choices.
- Do not add null checks, bounds checks, or defensive programming the C didn't have. If the C trusted its inputs, trust them.

If you find yourself wanting to "clean this up," stop. That is the signal to translate more literally, not less.

---

## 3. Type Mapping Rules

| C type                   | Java type                                         | Notes                                                    |
| ------------------------ | ------------------------------------------------- | -------------------------------------------------------- |
| `fixed_t` (16.16)        | `int`                                             | Use `Fixed` class static methods for arithmetic          |
| `int`                    | `int`                                             |                                                          |
| `short`                  | `int`                                             | mask with `& 0xFFFF` when reading unsigned from WAD      |
| `unsigned short`         | `int`                                             | always masked `& 0xFFFF` on read                         |
| `byte` / `unsigned char` | `int`                                             | masked `& 0xFF` on read; arrays stay `byte[]`            |
| `char*` (C string)       | `String`                                          | convert at the boundary; internal code uses `String`     |
| `char[8]` (lump name)    | `String`                                          | lump names are 8-char, null-padded; strip trailing nulls |
| C struct                 | Java class, **public fields**, no getters/setters |                                                          |
| C pointer to struct      | direct reference                                  |                                                          |
| C pointer arithmetic     | explicit index variable + base array              | see §5                                                   |
| C union                  | separate classes or a wrapper; case-by-case       |                                                          |
| `boolean` (C99)          | `boolean`                                         |                                                          |
| `NULL` return on error   | throw a typed exception                           | never return null where C returned NULL for failure      |

WAD file I/O uses `ByteBuffer` with `ByteOrder.LITTLE_ENDIAN`. Always.

---

## 4. Fixed-Point Math

All fixed-point operations go through `doom.fixed.Fixed`:

```java
Fixed.mul(a, b)      // FixedMul
Fixed.div(a, b)      // FixedDiv
Fixed.toInt(a)       // >> FRACBITS
Fixed.fromInt(i)     // << FRACBITS
```

`FRACBITS = 16`, `FRACUNIT = 1 << 16 = 65536`. Never use `float` or `double` to approximate fixed-point values. Demo compatibility and rendering correctness depend on bit-exact behavior.

---

## 5. Pointer Arithmetic Translation

C code like:

```c
byte* src = column + 3;
while (count--) *dest++ = *src++;
```

Becomes:

```java
int srcIdx = columnOffset + 3;
int destIdx = destOffset;
while (count-- > 0) {
    destArray[destIdx++] = srcArray[srcIdx++];
}
```

Rules:

- Every C pointer becomes **(array reference, index)** pair.
- Pointer comparisons become index comparisons against the same base.
- Pointer subtraction becomes index subtraction.
- Never use `ByteBuffer.position()` games to simulate pointers in hot paths — it's slower and harder to read.

---

## 6. Testing Contract

Every translated C file must have a JUnit 5 test class before it is considered complete. Minimum requirements:

- One test per public/exported function.
- For math and pure functions: verify against known values from the C source (compute them by hand or by running the C).
- For WAD-dependent code: tests use `reference-wads/doom1.wad` loaded once per class.
- For renderer code: compare output pixel buffers against reference PNGs stored in `src/test/resources/render-refs/`.

Run tests with `./gradlew test`. A module is not "done" until its tests pass.

---

## 7. Module Translation Workflow (the core loop)

For each C file, follow this exact sequence. **Do not skip steps.**

1. **Read** `reference/linuxdoom-1.10/<file>.c` and its `.h` in full.
2. **Check** `PORT_STATUS.md` — confirm dependencies are marked `Verified`. If not, stop and port those first.
3. **Summarize** the file in 5–10 lines in the PR description: what it does, key data structures, key functions, what depends on it.
4. **Translate** structures, enums, constants first (these are mechanical).
5. **Translate** pure/utility functions next.
6. **Translate** stateful/logic functions last.
7. **Generate** a JUnit test class covering every public function.
8. **Run** `./gradlew compileJava` — fix compile errors.
9. **Run** `./gradlew test` — fix test failures.
10. **Update** `PORT_STATUS.md`: mark status `Verified`, note line count, note any deviations from the C.
11. **Commit** with message `port: <file.c> → <Class.java>`.

---

## 8. Dependency Order (the build order)

Never port a module before its dependencies are Verified. The valid order:

1. `m_fixed` → 2. `m_bbox`, `tables`, `m_swap` → 3. `m_random`, `m_argv` → 4. `z_zone` → 5. `w_wad` → 6. `v_video` (framebuffer) → 7. `r_data` → 8. `r_main`, `r_bsp`, `r_plane`, `r_segs`, `r_things`, `r_draw` → 9. `p_*` (playsim) → 10. `s_sound`, `i_sound` → 11. `g_game`, `d_main` → 12. JavaFX shell (`ui/`)

---

## 9. JavaFX Layer (deferred)

Do not touch the JavaFX layer until the headless engine renders frames correctly into a `byte[320*200]` framebuffer and produces audio samples into buffers. The JavaFX shell is a thin adapter:

- Video: `WritableImage` backed by `PixelBuffer<IntBuffer>`; palette-convert the byte framebuffer to ARGB each frame; draw to `Canvas`.
- Audio: `javax.sound.sampled.SourceDataLine` for SFX; `javax.sound.midi.Sequencer` for music after MUS→MIDI conversion.
- Input: `Scene.setOnKeyPressed` / `setOnKeyReleased` mapped to Doom's event queue.
- Timing: `AnimationTimer` drives the game tick at 35 Hz (Doom's native tic rate).

The shell should be under 500 lines.

### 9a. Experimental scene-graph renderer (`doom.render3d`)

`doom.render3d` is an **opt-in alternative** world renderer (Launchpad
"Renderer" combo / `-fx3d`, `-sbs`, `-sbsv`) that draws with JavaFX's
native 3D scene graph. It is NOT a C translation and the faithfulness
rules of §2 do not apply *inside* it — but strict rules govern its
boundary:

- The software renderer stays the **default**. The definition of done
  (§13) is measured against the software path only.
- `doom.render3d` may only **read** engine state (`PSetup.*` arrays,
  `RData` tables, the player mobj). Never modify `doom.render` or
  `doom.play` for its benefit; demo determinism must be provably
  untouched.
- Fixed-point → float conversion (`x / 65536.0`) is allowed here at the
  view boundary only — the §4 fixed-point rules still bind everywhere
  else.
- Side-by-side modes run both renderers against the same tic; the
  weapon overlay reuses the software psprite pipeline via a
  `VVideo.viewBuffer` reference swap (restore it in a `finally`).
- Design, accepted fidelity deviations, and status: `3D_OVERHAUL.md`,
  `3D_SIDEBYSIDE_PLAN.md`, `3D_OVERHAUL_STATUS.md`. Keep the status
  file current when changing this package (same spirit as §12 for
  PORT_STATUS.md).

---

## 10. Things You Must Not Do

- Do not use `float` or `double` where the C uses `fixed_t`.
- Do not add null checks the C didn't have.
- Do not rename functions to "more Java-idiomatic" names.
- Do not use streams, lambdas, or functional constructs to replace C loops. Keep the loops.
- Do not introduce dependency injection, interfaces, or abstraction layers the C didn't have.
- Do not port a module before its dependencies are Verified.
- Do not mark a module Verified without passing tests.
- Do not modify `reference/linuxdoom-1.10/`.
- Do not use `MediaPlayer` for game audio — it's wrong tool.
- Do not "clean up" while translating. Separate passes if needed, and do cleanup never.

---

## 11. When Stuck

If a translation doesn't match expected behavior after two fix attempts:

1. Re-read the C source. Look for pointer arithmetic, byte-order assumptions, signed/unsigned casts, union access, or macro expansions you may have missed.
2. Check `reference/linuxdoom-1.10/<file>.h` for macros that affect behavior.
3. If still stuck, flag in `PORT_STATUS.md` with status `Blocked` and a one-paragraph description of the symptom. Ask the human.

Never silently commit broken code. Never mark Verified something that doesn't pass tests.

---

## 12. Commit Hygiene

- One commit per module when possible.
- Commit message format: `port: <file.c> → <Class.java>` for translations, `fix: <description>` for bug fixes, `test: <description>` for test changes.
- Update `PORT_STATUS.md` in the same commit as the module it describes.
- Never mix translation work with refactoring work in a single commit.

---

## 13. Definition of Done (for the whole project)

The port is done when:

1. `doom1.wad` loads without errors.
2. The title screen renders pixel-identical to the reference PNG.
3. The first level (`E1M1`) is playable start to finish.
4. SFX and music play.
5. Keyboard input controls movement, turning, firing, and menus.
6. A recorded demo (`.lmp`) plays back deterministically.

Stretch goals (post-MVP): gamepad support, resolution scaling, save/load, multiplayer.
