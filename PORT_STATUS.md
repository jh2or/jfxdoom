# PORT_STATUS

Dashboard for the linuxdoom-1.10 → Java/JavaFX port. Every C file has one row.
Update this file in the same commit as the module it describes (CLAUDE.md §14).

## Legend

- **Class** — `class = file prefix (PascalCase), method = camelCase of the rest` (CLAUDE.md §2).
  `r_main.c → RMain`, `m_fixed.c → Fixed` (explicit override per CLAUDE.md §4).
- **Status** —
  - `Verified` — translated, tests pass, behavior spot-checked against C.
  - `Translated` — translated, compiles, tests not yet green.
  - `Partial` — only some functions ported.
  - `Blocked` — see notes.
  - `Pending` — not yet started.
- **Tier** — Trivial (cheap), Standard (mid), Hard (heavy). See CLAUDE.md §9.

## 1 — Fixed point & math foundation

| C file       | Java class                | Tier     | Status   | LOC (C/Java) | Notes                                                    |
| ------------ | ------------------------- | -------- | -------- | ------------ | -------------------------------------------------------- |
| m_fixed.c    | `doom.fixed.Fixed`        | Trivial  | Verified | 88 / ~       | `FixedDiv2` overflow check throws, matches `I_Error`.    |
| m_bbox.c     | `doom.play.MBbox`         | Trivial  | Verified | 65 / ~       | `BOXTOP/BOTTOM/LEFT/RIGHT` constants inlined.            |
| m_swap.c    | `doom.wad.MSwap`          | Trivial  | Verified | 58 / ~       | WAD I/O uses `ByteBuffer(LITTLE_ENDIAN)` — see CLAUDE.md §3. |
| tables.c     | `doom.render.Tables`      | Trivial  | Verified | 2130 / ~     | Data stored in `src/main/resources/doom/render/*.dat` (LE ints) and loaded once.  |
| m_random.c   | `doom.play.MRandom`       | Trivial  | Verified | 77 / ~       | `rndtable` unsigned-char values → int, masked `& 0xFF`.  |
| m_argv.c     | `doom.system.MArgv`       | Trivial  | Verified | 57 / ~       | `myargc`/`myargv` are `public static`; strcasecmp → `equalsIgnoreCase`. |

## 2 — Zone memory, WAD loader, video

| C file    | Java class       | Tier | Status   | LOC (C/Java) | Notes                                                                      |
| --------- | ---------------- | ---- | -------- | ------------ | -------------------------------------------------------------------------- |
| z_zone.c  | `doom.wad.ZZone` | Hard | Verified | 468 / ~      | Tracker-model (JVM-backed); purge/tag semantics preserved. See deviation 3. |
| w_wad.c   | `doom.wad.WWad`  | Hard | Verified | 578 / ~      | `RandomAccessFile` handles kept open; `W_Profile` skipped (dev-only dump).  |
| v_video.c | `doom.ui.VVideo` | Hard | Verified | 494 / ~      | 5 `byte[320*200]` framebuffers; gamma table inlined. See deviation 6.       |

## 3 — Renderer (software)

| C file      | Java class                 | Tier | Status  | Notes                              |
| ----------- | -------------------------- | ---- | ------- | ---------------------------------- |
| r_data.c    | `doom.render.RData`        | Hard | Verified | Textures/flats/sprites/colormaps. `R_PrecacheLevel` stubbed (needs playsim). See deviation 7. |
| r_main.c    | `doom.render.RMain`        | Hard | Partial | Stub: `centery`, `centerx`, `extralight` only. Full body awaits r_bsp/p_setup. |
| r_bsp.c     | `doom.render.RBsp`         | Hard | Pending |                                    |
| r_plane.c   | `doom.render.RPlane`       | Hard | Pending |                                    |
| r_segs.c    | `doom.render.RSegs`        | Hard | Pending | Pointer-arithmetic heavy.          |
| r_things.c  | `doom.render.RThings`      | Hard | Pending | Pointer-arithmetic heavy.          |
| r_draw.c    | `doom.render.RDraw`        | Hard | Verified | Column/span blitters. `(byte[],int)` pointer pairs replace `byte*` globals. See deviation 8. |
| r_sky.c     | `doom.render.RSky`         | Standard | Verified | 63 / ~. Three globals + `R_InitSkyMap`. Commented-out `R_FlatNumForName` call in C preserved verbatim. |

## 4 — Playsim

| C file      | Java class            | Tier     | Status  | Notes                                          |
| ----------- | --------------------- | -------- | ------- | ---------------------------------------------- |
| p_tick.c    | `doom.play.PTick`     | Standard | Pending |                                                |
| p_setup.c   | `doom.play.PSetup`    | Standard | Pending |                                                |
| p_mobj.c    | `doom.play.PMobj`     | Standard | Pending |                                                |
| p_maputl.c  | `doom.play.PMaputl`   | Standard | Pending |                                                |
| p_map.c     | `doom.play.PMap`      | Standard | Pending |                                                |
| p_user.c    | `doom.play.PUser`     | Standard | Pending |                                                |
| p_pspr.c    | `doom.play.PPspr`     | Standard | Pending |                                                |
| p_enemy.c   | `doom.play.PEnemy`    | Standard | Pending |                                                |
| p_inter.c   | `doom.play.PInter`    | Standard | Pending |                                                |
| p_sight.c   | `doom.play.PSight`    | Standard | Pending |                                                |
| p_spec.c    | `doom.play.PSpec`     | Standard | Pending |                                                |
| p_doors.c   | `doom.play.PDoors`    | Standard | Pending |                                                |
| p_ceilng.c  | `doom.play.PCeilng`   | Standard | Pending |                                                |
| p_floor.c   | `doom.play.PFloor`    | Standard | Pending |                                                |
| p_lights.c  | `doom.play.PLights`   | Standard | Pending |                                                |
| p_plats.c   | `doom.play.PPlats`    | Standard | Pending |                                                |
| p_switch.c  | `doom.play.PSwitch`   | Standard | Pending |                                                |
| p_telept.c  | `doom.play.PTelept`   | Standard | Pending |                                                |
| p_saveg.c   | `doom.play.PSaveg`    | Standard | Pending |                                                |

## 5 — Sound

| C file      | Java class           | Tier     | Status  | Notes                              |
| ----------- | -------------------- | -------- | ------- | ---------------------------------- |
| s_sound.c   | `doom.sound.SSound`  | Standard | Pending |                                    |
| i_sound.c   | `doom.sound.ISound`  | Hard     | Pending | JavaFX audio backend, do last.     |
| sounds.c    | `doom.sound.Sounds`  | Trivial  | Pending | Data tables.                       |

## 6 — Game, menu, HUD, finale

| C file      | Java class             | Tier     | Status  |
| ----------- | ---------------------- | -------- | ------- |
| g_game.c    | `doom.system.GGame`    | Standard | Pending |
| d_main.c    | `doom.system.DMain`    | Standard | Pending |
| d_net.c     | `doom.system.DNet`     | Standard | Pending |
| m_menu.c    | `doom.ui.MMenu`        | Standard | Pending |
| m_misc.c    | `doom.system.MMisc`    | Standard | Pending |
| m_cheat.c   | `doom.play.MCheat`     | Trivial  | Pending |
| hu_lib.c    | `doom.ui.HuLib`        | Standard | Pending |
| hu_stuff.c  | `doom.ui.HuStuff`      | Standard | Pending |
| st_lib.c    | `doom.ui.StLib`        | Standard | Pending |
| st_stuff.c  | `doom.ui.StStuff`      | Standard | Pending |
| wi_stuff.c  | `doom.ui.WiStuff`      | Standard | Pending |
| am_map.c    | `doom.ui.AmMap`        | Standard | Pending |
| f_finale.c  | `doom.ui.FFinale`      | Standard | Pending |
| f_wipe.c    | `doom.ui.FWipe`        | Standard | Pending |
| dstrings.c  | `doom.system.Dstrings` | Trivial  | Pending |
| info.c      | `doom.play.Info`       | Trivial  | Pending | Thing/state tables — huge.      |
| doomdef.c   | `doom.system.Doomdef`  | Trivial  | Pending |
| doomstat.c  | `doom.system.Doomstat` | Trivial  | Partial | Stub: `gamemode`, `demoplayback` only; other cross-module globals added as dependents land. |
| d_items.c   | `doom.play.DItems`     | Trivial  | Pending |

## 7 — Platform adapters (last — JavaFX shell)

| C file        | Java class               | Tier | Status  | Notes                                              |
| ------------- | ------------------------ | ---- | ------- | -------------------------------------------------- |
| i_main.c      | —                        | —    | n/a     | Replaced by `doom.ui.JfxDoomApp` JavaFX entry.     |
| i_system.c    | `doom.system.ISystem`    | Standard | Pending |                                                |
| i_video.c     | `doom.ui.IVideo`         | Hard | Pending | JavaFX `PixelBuffer<IntBuffer>` + `Canvas`.        |
| i_net.c       | `doom.system.INet`       | Standard | Pending | Single-player stub first.                          |

## Deviations from C worth flagging

1. `m_fixed.c`: `FixedDiv` overflow branch throws `ArithmeticException` instead of calling `I_Error`.
   The behavior is the same (abort), but tests can catch the Java exception.
2. `tables.c`: data lives in `.dat` resources, not Java array literals, because
   Java's 64 KB method-bytecode limit makes inline literals of `finesine[10240]` unreliable.
   The values are byte-identical to `tables.c`.
3. `z_zone.c`: no pre-allocated byte buffer — the JVM heap backs every `Z_Malloc`.
   Each allocation is still tracked in a linked list with tag + user-back-ref,
   so `Z_FreeTags` / `Z_ChangeTag` / the `W_CacheLumpNum` cache-slot null-out
   behave as in C. `Z_CheckHeap` is a no-op (the list can't corrupt in Java).
   `Z_FreeMemory` counts only `PU_PURGELEVEL+` blocks — there's no "free block"
   state in the list because `Z_Free` unlinks.
4. `w_wad.c`: `W_Profile` (dev-only heap dump to `waddump.txt`) is intentionally
   not ported — it walks `memblock_t` via pointer arithmetic, which the tracker
   model doesn't support, and nothing downstream calls it.
5. `i_system.c`: only `I_Error` is ported so far, as `doom.system.ISystem.iError`,
   which throws `ISystem.DoomError`. The rest of `i_system.c` is deferred.
6. `v_video.c`:
   - `V_Init` allocates 4 independent `byte[320*200]` framebuffers instead of
     one `I_AllocLow(4*SCREENWIDTH*SCREENHEIGHT)` slab, because the C slab
     layout only matters for DOS planar video. The observable result — four
     writable screens of the same size — is identical.
   - `RANGECHECK` is always enabled in the port (the C guards it behind
     `#ifdef RANGECHECK`). The shipped DOS binaries ran without it for speed;
     we keep it always-on so tests catch misuse without changing the
     draw-path output.
   - `patch_t` is represented as `doom.render.Patch`, a thin wrapper that
     holds the parsed header plus the raw lump `byte[]` (since column walks
     pointer-arithmetic into the same buffer). The fuller Patch/texture
     machinery will be filled in with `r_data.c`.
7. `r_data.c`:
   - Typed globals the C gets via `Z_Malloc(n*sizeof(T), PU_STATIC, 0)` and
     then aliases through a typed pointer (`textures`, `texturecolumnlump`,
     `spritewidth`, …) are translated to plain Java typed arrays. Z_Malloc
     would force a `byte[]` cast we can't express without reinterpret casts.
     The zone is still used where C actually allocates a payload the zone
     owns (composite textures, temporary lumps freed via `Z_Free`), so
     purge/tag semantics stay faithful there.
   - `colormaps` is a flat `byte[]` of the COLORMAP lump's bytes; the C
     bumps its base up to a 256-byte boundary for fast `& 0xff` indexing,
     which is a DOS-era micro-optimisation — Java cannot express it and
     does not need to (indexing is by offset anyway).
   - `unsigned short texturecolumnofs[]` becomes `int[]` (always in
     `[0, 65535]`); the mask semantics carry over unchanged.
   - `R_PrecacheLevel` is stubbed (`UnsupportedOperationException`) until
     `p_setup`, `p_tick`, `r_sky`, `info` are ported — it references
     `sectors[]`, `sides[]`, `sprites[]`, `thinkercap`, `demoplayback`
     and `skytexture`.
8. `r_draw.c`:
   - Every C `byte*` global (`dc_colormap`, `dc_source`, `ds_colormap`,
     `ds_source`, `dc_translation`) becomes a `(byte[] base, int offset)`
     pair on `RDraw`. Callers are expected to set both halves when they
     would set the pointer in C. This preserves the C's ability to point
     mid-way into a larger buffer without copying.
   - `ylookup[]` and `columnofs[]` store integer offsets into
     `VVideo.screens[0]` rather than C-style `byte*` pointers; the
     draw inner loops resolve `screen[ylookup[y] + columnofs[x]]`.
   - The `unsigned` range-checks (`(unsigned)dc_x >= SCREENWIDTH`) are
     expressed as signed `dc_x < 0 || dc_x >= SCREENWIDTH` — identical
     semantics for all in-range values.
   - `viewimage` is kept as a `public static byte[]` for source parity
     but is never assigned or read; the C original is similarly dead.
   - Two `#if 0`-guarded loop-unrolled bodies in the C are intentionally
     not translated (they document optimisation experiments, not shipped
     behaviour).
   - `R_FillBackScreen` / `R_DrawViewBorder` are ported against
     `scaledviewwidth` / `viewheight` globals — functionally correct once
     `R_ExecuteSetViewSize` (in the not-yet-ported `r_main.c`) sets them.
