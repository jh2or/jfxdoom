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
| p_tick.c    | `doom.play.PTick`     | Standard | Partial | 158 / ~. Thinker list ops (`P_InitThinkers`/`P_AddThinker`/`P_RemoveThinker`/`P_AllocateThinker`/`P_RunThinkers`) + `leveltime`/`thinkercap` globals. `P_Ticker` deferred — it calls `P_PlayerThink`/`P_UpdateSpecials`/`P_RespawnSpecials` and reads `paused`/`menuactive`/`players[]`, none ported yet. Thinker.java upgraded from stub to full `actionf_t` union (see deviation 14). |
| p_setup.c   | `doom.play.PSetup`    | Standard | Partial | 708 / ~. Geometry-only port: P_LoadVertexes / P_LoadSegs / P_LoadSubsectors / P_LoadSectors / P_LoadNodes / P_LoadLineDefs / P_LoadSideDefs / P_LoadBlockMap / P_GroupLines + the orchestrator slice `pSetupLevelGeometry(lumpnum)` that runs all eight loaders + REJECT cache + GroupLines in C order. New `Subsector`/`Seg`/`Node` classes in render. P_LoadThings (calls P_SpawnMapThing) and full P_SetupLevel (player/deathmatch/S_Start/R_PrecacheLevel) defer until p_mobj.c. |
| p_mobj.c    | `doom.play.PMobj`     | Standard | Pending |                                                |
| p_maputl.c  | `doom.play.PMaputl`   | Standard | Partial | 883 / ~. Pure-math layer (`P_AproxDistance`, `P_PointOnLineSide`, `P_BoxOnLineSide`, `P_PointOnDivlineSide`, `P_MakeDivline`, `P_InterceptVector`, `P_LineOpening` + `opentop`/`openbottom`/`openrange`/`lowfloor` globals + `Divline` struct) plus the blockmap iterators (`P_BlockLinesIterator`, `P_BlockThingsIterator`) — `boolean(*func)(line_t*)` becomes `Predicate<Line>`, same return-false-to-stop contract. Iterators read `RMain.validcount` for the per-frame "already visited" stamp. Thing-position linkers (`P_SetThingPosition`/`P_UnsetThingPosition`) need `R_PointInSubsector` (r_bsp); intercept machinery (PIT_*, PathTraverse) needs intercept_t + trace globals. Both still ahead. |
| p_map.c     | `doom.play.PMap`      | Standard | Partial | Stub: `pChangeSector` returns false (no blocking mobj) until the mobj/blockmap iteration lands. T_MovePlane reads it (see deviation 21). |
| p_user.c    | `doom.play.PUser`     | Standard | Pending |                                                |
| p_pspr.c    | `doom.play.PPspr`     | Standard | Pending |                                                |
| p_enemy.c   | `doom.play.PEnemy`    | Standard | Pending |                                                |
| p_inter.c   | `doom.play.PInter`    | Standard | Pending |                                                |
| p_sight.c   | `doom.play.PSight`    | Standard | Pending |                                                |
| p_spec.c    | `doom.play.PSpec`     | Standard | Partial | Sector-graph walkers (`getSide`/`getSector`/`twoSided`/`getNextSector`/`pFind{Lowest,Highest,Next}{Floor,Ceiling}Surrounding`/`pFindMinSurroundingLight`/`pFindSectorFromLineTag`) + `Result` enum + `FLOORSPEED` + animated textures (`Anim`/`AnimDef`, `animdefs[22]`, `P_InitPicAnims`) + per-tic `P_UpdateSpecials` (animation, line-scroll 48, button countdown — level timer `G_ExitLevel` deferred, deviation 23) + level-start `P_SpawnSpecials` (sector specials 1/2/3/4/8/9/10/12/13/14/17 + line 48 scrolls + -avg/-timer parsing) + `EV_DoDonut` (with the famous `(!flags & ML_TWOSIDED)` precedence-bug branch preserved verbatim — see deviation 24) + `P_CrossSpecialLine` (60+ case walk-trigger dispatcher with monster filter and projectile early-return) + `P_ShootSpecialLine` (3-case impact dispatcher). `P_PlayerInSpecialSector` waits on player.powers[]/P_DamageMobj. |
| p_doors.c   | `doom.play.PDoors`    | Standard | Verified | 717 / ~. T_VerticalDoor + EV_DoDoor + EV_DoLockedDoor + EV_VerticalDoor + P_SpawnDoorCloseIn30 + P_SpawnDoorRaiseIn5Mins. vldoor_e (8 values) as int constants. EV_VerticalDoor / EV_DoLockedDoor read `Mobj.player.cards[]` for keycard locks (Player + Mobj.player added in this round); the message strings (PD_BLUEK / PD_REDK / PD_YELLOWK / PD_BLUEO / PD_REDO / PD_YELLOWO from d_englsh.h) live on Dstrings. The `#if 0` sliding-door block is skipped (dead C code). The C's redundant `if (!p) return 0;` checks inside switch cases and the unused `secnum` local in EV_VerticalDoor are preserved verbatim (CLAUDE.md §2). |
| p_ceilng.c  | `doom.play.PCeilng`   | Standard | Verified | 279 / ~. Full port: T_MoveCeiling + EV_DoCeiling + active-ceiling slot table (MAXCEILINGS=30) + EV_CeilingCrushStop + P_ActivateInStasisCeiling. Sound calls go through SSound stub; `acv=NULL` pause sentinel maps to `acp1=null` (deviation 14). |
| p_floor.c   | `doom.play.PFloor`    | Standard | Verified | 555 / ~. Full port: `T_MovePlane` (generic floor/ceiling mover), `T_MoveFloor` (per-tic floor thinker), `EV_DoFloor` (13-way `floor_e` dispatch), `EV_BuildStairs` (`build8`/`turbo16`). `floormove_t` is the `FloorMove extends Thinker` inner class. Sector-pointer-difference (`tsec - sectors`) becomes a linear-scan `sectorIndex(Sector)` helper. The donutRaise dispatch lives in p_spec.c (deferred — only the per-tic completion path lives here). |
| p_lights.c  | `doom.play.PLights`   | Standard | Verified | 357 / ~. Five sector-lighting thinker subtypes (`FireFlicker`/`LightFlash`/`Strobe`/`Glow`) subclass `Thinker`; `acp1` bound via method reference. Z_Malloc elided for extended thinkers — they're Java objects (deviation 16). |
| p_plats.c   | `doom.play.PPlats`    | Standard | Verified | 295 / ~. Full port: T_PlatRaise + EV_DoPlat + activeplats[MAXPLATS] slot table + EV_StopPlat + P_ActivateInStasis + add/remove. plat_e and plattype_e as int constants (same rationale as PCeilng's ceiling_e). raise{AndChange,ToNearestAndChange} reads Side.sector.floorpic. |
| p_switch.c  | `doom.play.PSwitch`   | Standard | Verified | 655 / ~. Button/switch infrastructure (`alphSwitchList[]`, `P_InitSwitchList`, `P_StartButton`, `P_ChangeSwitchTexture`) plus the full `P_UseSpecialLine` line-special "use" dispatcher (50+ case labels routing to EV_VerticalDoor / EV_DoLockedDoor / EV_DoFloor / EV_DoCeiling / EV_DoDoor / EV_DoPlat / EV_BuildStairs / EV_DoDonut / EV_LightTurnOn / G_ExitLevel / G_SecretExitLevel). Preserves the C quirk `buttonlist->soundorg` (always slot-0 for switch SFX). |
| p_telept.c  | `doom.play.PTelept`   | Standard | Partial | Stub: `evTeleport` returns 0 (no teleport happened) until P_TeleportMove (p_map.c) + P_SpawnMobj (p_mobj.c) + the mobj_t.{type,subsector,flags,floorz,momx/y/z,reactiontime} fields land. Same pattern as PMap.pChangeSector (deviation 21). Tests that need to drive a successful teleport flip `teleportResult`. |
| p_saveg.c   | `doom.play.PSaveg`    | Standard | Pending |                                                |

## 5 — Sound

| C file      | Java class           | Tier     | Status  | Notes                              |
| ----------- | -------------------- | -------- | ------- | ---------------------------------- |
| s_sound.c   | `doom.sound.SSound`  | Standard | Partial | Stub: `sStartSound(Object, int)` as no-op so plane-movers (PCeilng, PDoors, PPlats, ...) can call it. Full mixer/attenuation/channel bookkeeping lands with i_sound. |
| i_sound.c   | `doom.sound.ISound`  | Hard     | Pending | JavaFX audio backend, do last.     |
| sounds.c    | `doom.sound.Sounds`  | Trivial  | Verified | 228 / ~. `sfxenum_t`/`musicenum_t` as `public static final int` constants; `S_sfx[sfx_chgun].link` self-reference patched up after array fill (see deviation 17). |

## 6 — Game, menu, HUD, finale

| C file      | Java class             | Tier     | Status  |
| ----------- | ---------------------- | -------- | ------- |
| g_game.c    | `doom.system.GGame`    | Standard | Pending |
| d_main.c    | `doom.system.DMain`    | Standard | Pending |
| d_net.c     | `doom.system.DNet`     | Standard | Pending |
| m_menu.c    | `doom.ui.MMenu`        | Standard | Pending |
| m_misc.c    | `doom.system.MMisc`    | Standard | Partial | 57 / ~. Just `M_WriteFile` + `M_ReadFile` — the rest (defaults, screenshot, draw-text) needs unported globals. `mReadFile` returns `byte[]` directly in place of the C's `byte**` out-param + length return; see deviation 18. |
| m_cheat.c   | `doom.play.MCheat`     | Trivial  | Verified | 101 / ~. `cheat_xlate_table` is `int[256]`; `cheatseq_t.p` uses sentinel `-1` to express C's NULL "not yet initialized". See deviation 9. |
| hu_lib.c    | `doom.ui.HuLib`        | Standard | Verified | 294 / ~. Text-line / scrolling / input widgets. `boolean*` "on" pointers translate as `boolean[]` holders (see deviation 19). `SHORT()` is a no-op since `Patch.width/height` are already parsed int. |
| hu_stuff.c  | `doom.ui.HuStuff`      | Standard | Pending |
| st_lib.c    | `doom.ui.StLib`        | Standard | Verified | 262 / ~. Number/Percent/MultIcon/BinIcon widgets. `int*`/`boolean*` caller-owned holders translate as `int[1]`/`boolean[1]` (deviation 19). `SHORT()` is a no-op since Patch fields are pre-parsed int. BG=4 (status-bar layer) distinct from HuLib's BG=1. |
| st_stuff.c  | `doom.ui.StStuff`      | Standard | Partial | Stub: `ST_HEIGHT`/`ST_WIDTH`/`ST_Y` constants for st_lib. Status-bar logic lands when its player/sound deps arrive. |
| wi_stuff.c  | `doom.ui.WiStuff`      | Standard | Pending |
| am_map.c    | `doom.ui.AmMap`        | Standard | Partial | Stub only: `automapactive` flag for hu_lib's erase path. Full automap lands later. |
| f_finale.c  | `doom.ui.FFinale`      | Standard | Pending |
| f_wipe.c    | `doom.ui.FWipe`        | Standard | Verified | 302 / ~. Melt+ColorXForm screen wipes. `short*` aliasing of the `byte[]` framebuffers is translated as 2-byte-pair copies (see deviation 12); `I_ReadScreen` lands in a new `doom.ui.IVideo` stub. |
| dstrings.c  | `doom.system.Dstrings` | Trivial  | Verified | 72 / ~. `endmsg[]` preserves the two missing-comma string concatenations from the C (see deviation 10). d_englsh.h constants ported into callers as they land. |
| info.c      | `doom.play.Info`       | Trivial  | Partial | Three enums from info.h ported as `public static final int` constants (deviation 17): `spritenum_t` (138 entries), `statenum_t` (967), `mobjtype_t` (137). The `state_t` / `mobjinfo_t` structs and the corresponding 4670-line data tables in info.c land with p_mobj.c. |
| doomdef.c   | `doom.system.Doomdef`  | Trivial  | Verified | No executable content in C (just includes). `Doomdef.java` holds the screen/timing constants lower modules need; enums/keys added as callers land. |
| doomstat.c  | `doom.system.Doomstat` | Trivial  | Partial | Stub: `gamemode`, `demoplayback` only; other cross-module globals added as dependents land. |
| d_items.c   | `doom.play.DItems`     | Trivial  | Verified | 138 / ~. `weaponinfo[NUMWEAPONS]` static table indexed by `weapontype_t`. `weapontype_t` and `ammotype_t` (doomdef.h) lifted as `public static final int` constants on Doomdef. Each `WeaponInfo` is an immutable nested record; state IDs come from `Info`. |

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
9. `m_cheat.c`:
   - `cheatseq_t.p` is translated as an `int` index, with `-1` meaning
     "pointer not yet initialized" — C detects this via `if (!cht->p)`
     on a zero-initialized struct. Everywhere the C sets `p = sequence`,
     the Java sets `p = 0`.
   - `cheat_xlate_table` is `int[256]` (values already masked to `[0,255]`)
     instead of `unsigned char[256]`; per CLAUDE.md §3, `unsigned char`
     values being compared or indexed live as `int` in Java.
   - `SCRAMBLE` in the C header is a macro; the Java port exposes it as
     `MCheat.scramble(int)` so tests can round-trip it.
10. `dstrings.c`:
    - The C `endmsg[]` initializer is missing commas in two places
      (after `"...see if i care."` and after `"...for thinking about
      leaving."`), which the C preprocessor silently concatenates into
      single strings. CLAUDE.md §2 says preserve bugs — the Java port
      reproduces both merged entries verbatim.
    - d_englsh.h is a header full of `#define` UI strings consumed at
      call sites. Rather than translate it wholesale into a DEnglsh
      class, each string lands in its eventual caller's class
      (MMenu, HuStuff, PInter, FFinale, ...) as those modules port.
      `QUITMSG` lives in `Dstrings` since `endmsg[0]` needs it.
12. `f_wipe.c`:
    - The melt path casts `byte* wipe_scr_{start,end,mid}` to `short*`
      so it can move two palette pixels per array cell. Java cannot
      reinterpret a `byte[]` as `short[]` without copying, so each
      `short` access becomes a paired `(b[i], b[i+1])` byte copy at
      the equivalent byte offset. The observable framebuffer state is
      bit-identical to the C.
    - Per-column melt offsets `static int* y` land as a plain `int[]`
      because the C allocates it through `Z_Malloc(width*sizeof(int),
      PU_STATIC, 0)` — the same rationale as `r_data.c` deviation #7.
    - The function-pointer table `wipes[wipeno*3 + n]` is flattened to
      three small dispatch helpers (`wipeInit`/`wipeDo`/`wipeExit`)
      that switch on `wipeno`. Call sequence is identical.
    - `I_ReadScreen` is the only i_video.c entry the wipe references;
      it lands in a new `doom.ui.IVideo` as `iReadScreen(byte[])` so
      the rest of i_video.c can stay deferred.
14. `d_think.h` / `p_tick.c`:
    - The C `actionf_t` union holds three same-storage function
      pointers (`acv`, `acp1`, `acp2`). Java has no unions, so each
      slot lives in its own typed field inside `Thinker.Actionf`. Only
      one slot is populated for any given thinker — same contract as
      the C, just minus the shared storage.
    - `P_RemoveThinker` stamps `function.acv = (actionf_v)(-1)` as a
      "marked for removal" sentinel. Java cannot cast an int to a
      function reference, so the sentinel is a `boolean removed` on
      `Thinker`. `P_RunThinkers` checks this flag where the C compared
      against -1. Observable semantics are identical — no C code
      compares `acv` to any value other than the -1 sentinel.
    - `P_RunThinkers` calls `Z_Free(currentthinker)` on removed
      entries; thinkers are Java objects (not zone-allocated byte
      buffers), so dropping the reference via unlink is enough and the
      GC reclaims them. Extended thinker subtypes that do own a
      zone-allocated payload (once ported) will need to release it
      explicitly before returning from their removal path — same as
      the C's caller-owned `Z_Malloc` model.
16. `p_lights.c` + extended thinkers:
    - The C casts spawn-time function pointers via
      `(actionf_p1) T_FireFlicker`, relying on
      `fireflicker_t*`/`thinker_t*` being pointer-compatible (the C
      struct embeds `thinker_t` as its first member). The Java port
      makes the extended types (`FireFlicker`, `LightFlash`, `Strobe`,
      `Glow`) subclass `Thinker`, binds `acp1` to a `Consumer<Thinker>`
      method reference, and casts back inside the tick function — same
      pattern the C implicitly relies on.
    - The C allocates every extended thinker via
      `Z_Malloc(sizeof(*t), PU_LEVSPEC, 0)` so the level-teardown
      `Z_FreeTags(PU_LEVSPEC)` sweep can release them. Java extended
      thinkers are plain objects, not zone-tracked byte buffers, so
      the GC reclaims them once `P_RunThinkers` unlinks a removed
      thinker. The `PU_LEVSPEC` sweep no longer needs to find them,
      so the zMalloc call is elided. Same rationale as deviation 14
      for the core `Thinker` class.
17. `sounds.c`:
    - The two C enums `musicenum_t` and `sfxenum_t` become `public
      static final int` constants rather than Java `enum`, because
      `S_music[]`/`S_sfx[]` are indexed by them and the C arithmetic
      (`NUMMUSIC = last + 1`) carries over cleanly as integers.
    - `S_sfx[sfx_chgun].link` is `&S_sfx[sfx_pistol]` — a self-reference
      inside one array initializer. Java cannot express that in a
      literal; the static initializer sets `link` to the already-built
      `pistol` entry since the `chgun` slot is populated after it.
    - `data` (void* in C) and `specialdata`-style Object fields are
      left as `Object` until the sound loader lands.
18. `m_misc.c`:
    - `M_ReadFile`'s C signature is `int M_ReadFile(char const*, byte**)`
      — length via return, buffer via out-param. The Java port collapses
      both onto one return value (`byte[] mReadFile(String)`), since
      callers only used the length for bounds-checks that `byte[].length`
      now provides.
    - The C `Z_Malloc(length, PU_STATIC, NULL)` buffer becomes a plain
      `byte[]` (from `Files.readAllBytes`). No existing caller re-tagged
      or explicitly `Z_Free`d the buffer — they used it once and dropped
      it — so the zone hook was never load-bearing. Same rationale as
      deviations 7 and 14 for typed allocations the C tracked only for
      GC-like cleanup.
    - Only the two standalone file-I/O helpers are ported. `M_DrawText`
      needs `hu_font`/`V_DrawPatch`; `M_LoadDefaults`/`M_SaveDefaults`
      touch ~30 as-yet-unported extern globals (`key_*`, `mouseb*`,
      `joyb*`, `snd_SfxVolume`, ...); `M_ScreenShot` needs the PCX
      encoder. All three land when their dependencies do.
19. `hu_lib.c`:
    - Widgets carry a `boolean*` pointer to the caller's on/off flag
      (`hu_stext_t.on`, `hu_itext_t.on`). Java has no way to take the
      address of a `boolean`, so the pointer is translated as a
      1-element `boolean[]` the caller supplies and mutates in place.
      Same rationale as any pointer-to-mutable-scalar translation;
      observable read/write semantics match the C.
    - `static boolean lastautomapactive = true;` inside
      `HUlib_eraseTextLine` is assigned at the end of the function but
      never read anywhere — dead code in the C. Lifted to a
      package-private field on `HuLib` so the write still happens
      (CLAUDE.md §2: preserve bugs/quirks verbatim).
    - `SHORT(patch->width/height)` is a no-op in the Java port because
      `Patch.fromLump` already byte-swaps the lump header during parse
      (see deviation 6). Direct field reads are equivalent.
    - `noterased` is a `#define` alias for `viewwindowx` in the C; it
      has no presence in the Java port — the erase path reads
      `RDraw.viewwindowx` directly.
20. `am_map.c`:
    - Stub only: `automapactive` is live, the rest of the automap is
      deferred. `hu_lib` reads the flag in its erase path; a fuller
      port replaces this class in place without changing the public
      surface.
23. `p_spec.c` P_UpdateSpecials level timer:
    - The `if (levelTimer) { levelTimeCount--; if (!levelTimeCount)
      G_ExitLevel(); }` block at the top of the C routine is ported
      with the `G_ExitLevel()` call commented out — g_game.c is
      unported. The countdown still happens; only the timeout exit
      action is skipped. Tests assert the decrement behavior.
    - The `{-1}` istexture sentinel at the end of `animdefs[]` in the
      C maps to a null gap in the Java `AnimDef[]` (Java has no
      tri-state boolean). `P_InitPicAnims` stops on the first null —
      same loop shape as the C.
    - `anim_t* lastanim` (pointer) becomes `int numanims` (count).
      The C iterates `for (anim = anims; anim < lastanim; anim++)` —
      we iterate `for (int n = 0; n < numanims; n++)`. Observable
      identical.
    - C's `memset(&buttonlist[i], 0, sizeof(button_t))` at button
      timeout is replaced with explicit field clears. The Button
      slot object stays in the table (see PSwitch deviation); only
      its contents reset.
22. `p_ceilng.c` + `s_sound.c`:
    - `ceiling_e` (6 values) lands as `public static final int` constants
      on PCeilng rather than a Java enum, since the switch-on-ordinal +
      fall-through labels in T_MoveCeiling / EV_DoCeiling translate
      literally from the C when the values stay as ints.
    - The pause-ceiling sentinel `thinker.function.acv = (actionf_v)NULL`
      maps to `acp1 = null` in Java (deviation 14). PTick's pRunThinkers
      already gates on `acp1 != null`, so a paused ceiling stays parked
      in the thinker list exactly like the C does — and restoring
      `acp1 = PCeilng::tMoveCeiling` resumes it.
    - Sound callbacks run through a no-op `SSound.sStartSound` stub
      (`origin` is `Object` until Mobj/Degenmobj share a listener-origin
      type — see SSound javadoc). The call-site shape is unchanged.
24. `p_spec.c` EV_DoDonut + `g_game.c` exit hooks:
    - The C inner-loop guard at p_spec.c line 1187 reads
      `(!s2->lines[i]->flags & ML_TWOSIDED)`. The C `!` operator
      binds tighter than `&`, so the LHS is `(!flags) & ML_TWOSIDED`
      — which is always 0 (since `!x` is 0 or 1, and `1 & 4 == 0`).
      The intent was clearly `!(flags & ML_TWOSIDED)`, but the bug
      means EV_DoDonut accepts the *first* line of s2 whose
      backsector is not s1, even one-sided lines. Per CLAUDE.md §2,
      faithfully preserved as a `(flags == 0 ? 1 : 0) & ML_TWOSIDED`
      Java expression with an explanatory comment.
    - `G_ExitLevel` and `G_SecretExitLevel` land as no-op stubs on
      `doom.system.GGame` that just flip `gameaction = ga_completed`
      and (for the secret variant) latch `secretexit = 1`. The full
      G_DoCompleted intermission flow lives with d_main / wi_stuff;
      the EV_ trigger callers in p_switch / p_spec only need the
      latch to fire. Same pattern as SSound's no-op stub.

21. `p_map.c` + `p_floor.c`:
    - `P_ChangeSector` is stubbed on `PMap` to return `false` by default
      — i.e. "no mobj in the way". T_MovePlane reads this value to
      decide between the normal and crush paths. In an unfurnished
      level (no mobjs spawned yet) this is the only answer the C code
      could return anyway; tests that need the crush path flip
      `PMap.changeSectorResult` before invoking T_MovePlane. The
      override field disappears once `P_ChangeSector`'s mobj-blockmap
      iteration lands.
    - `result_e` (`ok`/`crushed`/`pastdest`) lives on `PSpec` as a Java
      enum — ordinals match the C enum (0/1/2). `FLOORSPEED` also lives
      there since the relevant #defines are in p_spec.h.
    - Only T_MovePlane is ported from p_floor.c; T_MoveFloor,
      EV_DoFloor, and EV_BuildStairs reach into yet-to-port helpers
      and land with the fuller p_spec port.
