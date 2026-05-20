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
| r_main.c    | `doom.render.RMain`        | Hard | Partial | 898 / ~. BSP descent (`R_PointOnSide` + `R_PointOnSegSide` + `R_PointInSubsector`) + angle/distance helpers (`R_PointToAngle`, `R_PointToAngle2`, `R_PointToDist`) + the per-frame setup chain: `R_AddPointToBox`, `R_InitTables` (no-op like the C, table data preloaded from Tables.dat), `R_InitPointToAngle` (no-op), `R_InitTextureMapping` (builds viewangletox / xtoviewangle / clipangle), `R_InitLightTables` (builds zlight[][] from RData.colormaps), `R_SetViewSize` / `R_ExecuteSetViewSize` (full body — viewwidth / viewheight / centerxfrac / projection / yslope / distscale / scalelight / pspritescale, calls RDraw.rInitBuffer + R_InitTextureMapping), `R_Init` (orchestrator, skips printf chatter), `R_SetupFrame` (per-tic player-vector copy + fixedcolormap → scalelightfixed wiring), `R_RenderPlayerView` (clears RBsp/RPlane/RThings buffers, walks BSP, draws planes / masked — pixel-write side currently no-ops via the RPlane/RSegs/RThings stubs), `R_ScaleFromGlobalAngle` (used by r_segs once it ports). RMain stays Partial only because the colfunc/spanfunc dispatch table the C uses for the detail-shift switch is inlined into RDraw call sites (Java doesn't need the function-pointer trampoline). Light constants + FIELDOFVIEW / DISTMAP land here. RSegs gains `rwNormalangle` / `rwDistance` stubs so R_ScaleFromGlobalAngle compiles cleanly. |
| r_bsp.c     | `doom.render.RBsp`         | Hard | Verified | 580 / ~. Full port: BSP traversal (R_RenderBSPNode + R_Subsector), seg clipping (R_AddLine + R_ClipSolidWallSegment + R_ClipPassWallSegment), bbox visibility (R_CheckBBox + checkcoord LUT), and the per-frame clip-list / drawseg list reset (R_ClearDrawSegs + R_ClearClipSegs). The C `goto clipsolid:` / `goto clippass:` in R_AddLine and `goto crunch:` in R_ClipSolidWallSegment translate as labelled boolean toggles per CLAUDE.md §2. New `Drawseg` (drawseg_t) struct in render. New cliprange / drawseg arrays on RBsp. Angle-comparison wraparound uses `Integer.compareUnsigned` (same pattern as PUser / PEnemy). |
| r_plane.c   | `doom.render.RPlane`       | Hard | Verified | 453 / ~. Full port: visplane allocation (Visplane / visplanes[] / R_FindPlane / R_CheckPlane), per-frame setup (R_ClearPlanes — basexscale/baseyscale derived from viewangle), span dispatch (R_MakeSpans), per-row span generation (R_MapPlane: cachedheight/distance/xstep/ystep with row caching, ds globals populated from yslope/distscale/xtoviewangle/zlight, dispatches to RDraw.rDrawSpan/rDrawSpanLow), and end-of-frame paint (R_DrawPlanes: walks visplanes, dispatches sky columns through RData.rGetColumn + RDraw.rDrawColumn or regular flats through W_CacheLumpNum + R_MakeSpans). The detail-shift dispatch (rDrawColumn vs rDrawColumnLow) is inlined at call sites instead of using a function-pointer trampoline (deviation 31). Z_ChangeTag(PU_CACHE) demote calls dropped — Java GC handles cache release once dsSource is overwritten on the next frame (same rationale as deviation 7). |
| r_segs.c    | `doom.render.RSegs`        | Hard | Verified | 746 / ~. Full port: R_StoreWallRange (per-seg setup — rwDistance via R_PointToDist + perpendicular projection, scale at both ends via R_ScaleFromGlobalAngle, silhouette + sprite-clip array assignment, midtexture pegging, light-band pick from frontsector.lightlevel + extralight + horizontal/vertical bias, masked-midtexture column-buffer slice into RPlane.openings, marker visplane carve via R_CheckPlane, world-edge stepping via centeryfrac>>4 fraction, sprite-clip rows memcpy'd from ceilingclip/floorclip into openings) + R_RenderSegLoop (per-column dispatch — calculates yl/yh from topfrac/bottomfrac, marks floor/ceiling visplane spans, computes texture column from rwCenterangle + xtoviewangle, picks light from rwScale, dispatches single/two-sided wall tiers through RDraw.rDrawColumn or rDrawColumnLow, saves masked-mid texturecolumn for later) + R_RenderMaskedSegRange (called by r_things' masked pass when ported — picks light, walks columns, dispatches RThings.rDrawMaskedColumn). The pointer-arithmetic-heavy `lastopening - rw_x` aliasing pattern translates as `(short[] base, int offset)` pairs on Drawseg per CLAUDE.md §5 (deviation 32). The C `goto` not needed; control flow is straight. RThings stub gains `negonearray`, `mfloorclip` / `mceilingclip` (clip-row references the masked path writes into), `spryscale` / `sprtopscreen`, and `rDrawMaskedColumn` no-op. Drawseg gains `SIL_NONE/SIL_BOTTOM/SIL_TOP/SIL_BOTH` constants. |
| r_things.c  | `doom.render.RThings`      | Hard | Verified | 989 / ~. Full port: Vissprite (vissprite_t) + Spritedef (spritedef_t) + Spriteframe (spriteframe_t) structs in render. Vissprite allocation pool (vissprites[MAXVISSPRITES=128] + vsP) + R_NewVisSprite + R_ClearSprites. Sprite startup R_InitSpriteDefs / R_InstallSpriteLump / R_InitSprites walks WWad.lumpinfo[] between S_START/S_END, parsing 4-char lump-name prefixes (replaces the C `*(int*)name == intname` punning with Java String.startsWith). The C tri-state `rotate ∈ {-1, 0, 1}` translates as a boolean + lump[]==-1 sentinel (deviation 33). R_DrawMaskedColumn walks the column-post stream by byte offset (each post: `topdelta, length, pad, pixels[length], pad`, terminated by topdelta == 0xff) — no `column_t` struct cast. R_DrawVisSprite reads patch headers from raw lump bytes via readShortLE / readIntLE helpers, picks the colfunc mode (0/1/2 = base/fuzz/translated) instead of the C function pointer, and dispatches per-column rDrawMaskedColumn. R_ProjectSprite (mobj sprite projection — fixedcolormap / fullbright / shadow / diminished light branches), R_AddSprites (per-subsector sec.thinglist walk with validcount stamp), R_DrawPSprite + R_DrawPlayerSprites (held-weapon overlay with invisibility flicker), R_SortVisSprites (ascending-scale insertion sort via doubly-linked list), R_DrawSprite (per-sprite drawseg-aware clipping with the SIL_TOP/SIL_BOTTOM/SIL_BOTH silhouette masks + maskedtexturecol re-render trigger), R_DrawMasked (sort + back-to-front sprite draw + remaining masked-mid textures + psprite overlay). Mobj picks up FF_FULLBRIGHT / FF_FRAMEMASK constants. Vissprite.colormap is `(byte[] base, int offset)` per the renderer's lighttable convention. |
| r_draw.c    | `doom.render.RDraw`        | Hard | Verified | Column/span blitters. `(byte[],int)` pointer pairs replace `byte*` globals. See deviation 8. |
| r_sky.c     | `doom.render.RSky`         | Standard | Verified | 63 / ~. Three globals + `R_InitSkyMap`. Commented-out `R_FlatNumForName` call in C preserved verbatim. |

## 4 — Playsim

| C file      | Java class            | Tier     | Status  | Notes                                          |
| ----------- | --------------------- | -------- | ------- | ---------------------------------------------- |
| p_tick.c    | `doom.play.PTick`     | Standard | Verified | 158 / ~. Full port: thinker list ops (`P_InitThinkers`/`P_AddThinker`/`P_RemoveThinker`/`P_AllocateThinker`/`P_RunThinkers`) + `P_Ticker` (per-tic playsim entry: paused / menuactive bail-outs, P_PlayerThink for every active seat, P_RunThinkers, P_UpdateSpecials, P_RespawnSpecials, leveltime++). Globals: `leveltime`/`gametic`/`thinkercap`. Doomstat picks up `paused`. New `MMenu` stub (m_menu.c) carrying `menuactive`. Thinker.java has the full `actionf_t` union (deviation 14). |
| p_setup.c   | `doom.play.PSetup`    | Standard | Partial | 708 / ~. Geometry-only loaders + P_LoadThings (THINGS lump → P_SpawnMapThing dispatch with the C's commercial-IWAD monster-type filter preserved). `pSetupLevelGeometry(lumpnum)` runs the eight geometry loaders + REJECT cache + GroupLines in C order; THINGS loader is callable separately. New `Subsector`/`Seg`/`Node` classes in render. New cross-module pools: `deathmatchstarts[10]` + `deathmatchPIndex` (the C's deathmatch_p as an int index, deviation 26) + `playerstarts[MAXPLAYERS]`. Full P_SetupLevel orchestrator (S_Start/R_PrecacheLevel/G_DeathMatchSpawnPlayer) still defers. |
| p_mobj.c    | `doom.play.PMobj`     | Standard | Verified | 988 / ~. Full port: core lifecycle + monster respawn + item respawn queue + level-load placement (P_SpawnPlayer + P_SpawnMapThing) + projectile spawn (P_SpawnMissile + P_SpawnPlayerMissile, the latter using PMap.pAimLineAttack's auto-aim output). New `VIEWHEIGHT` constant (p_local.h). Player picks up playerstate/refire/damagecount/bonuscount/health/viewheight + PST_LIVE/DEAD/REBORN constants. GGame picks up playeringame[]/players[]/consoleplayer + a stub gPlayerReborn. PPspr stub (pSetupPsprites/pMovePsprites/pDropWeapon no-ops). HuStuff stub (huStart no-op). StStuff picks up stStart no-op. New `Mapthing` struct (mapthing_t) with MTF_AMBUSH bit. |
| p_maputl.c  | `doom.play.PMaputl`   | Standard | Verified | 883 / ~. Full port: pure-math layer (`P_AproxDistance`, `P_PointOnLineSide`, `P_BoxOnLineSide`, `P_PointOnDivlineSide`, `P_MakeDivline`, `P_InterceptVector`, `P_LineOpening`) + blockmap iterators (`P_BlockLinesIterator`, `P_BlockThingsIterator`) with `Predicate<Line>`/`Predicate<Mobj>` callbacks + intercept machinery (`PIT_*Intercepts`, `P_TraverseIntercepts`, `P_PathTraverse`) with the `intercepts[128]`/`intercept_p`/`trace`/`earlyout`/`ptflags` globals + thing-position linkers (`P_SetThingPosition`/`P_UnsetThingPosition`) using `RMain.rPointInSubsector` for the BSP descent. New helper structs `Divline`, `Intercept`. |
| p_map.c     | `doom.play.PMap`      | Standard | Verified | 1339 / ~. Full port: movement-and-collision core (`PIT_CheckLine`, `PIT_CheckThing`, `P_CheckPosition`, `P_TryMove`), slide stack, use-line stack, telefrag (`PIT_StompThing` + `P_TeleportMove`), hitscan combat (`PTR_AimTraverse` + `P_AimLineAttack` + `PTR_ShootTraverse` + `P_LineAttack`), splash damage (`PIT_RadiusAttack` + `P_RadiusAttack`), and sector crush (`PIT_ChangeSector` + `P_ChangeSector` real body — `changeSectorOverride` flag preserves the deviation-21 stub semantics for plane-mover unit tests that don't populate full worlds). The C `(damage+MAXRADIUS)<<FRACBITS` double-shift bug preserved verbatim per deviation 28. Globals shared with PSight (`topslope`/`bottomslope`) and PMobj (`attackrange`). |
| p_user.c    | `doom.play.PUser`     | Standard | Verified | 386 / ~. Full port: P_Thrust + P_CalcHeight + P_MovePlayer + P_DeathThink + P_PlayerThink. New `Ticcmd` struct (d_ticcmd.h ticcmd_t) with int-widened fields per CLAUDE.md §3. Doomdef picks up powertype_t (pw_*/NUMPOWERS) + buttoncode_t (BT_*/BTS_*) constants. Player picks up cmd/bob/deltaviewheight + the remaining d_player.h fields needed by p_inter (powers[]/weaponowned[]/ammo[]/maxammo[]/readyweapon/pendingweapon/cheats/refire/attackdown/usedown/killcount/itemcount/secretcount/armorpoints/armortype/backpack/frags[]/attacker) plus CF_* cheat-bit constants. PPspr stub gains pMovePsprites. PSpec stub gains pPlayerInSpecialSector (no-op until p_inter ports P_DamageMobj). The C `delta < ANG5 || delta > (unsigned)-ANG5` 32-bit wraparound check uses `Integer.compareUnsigned` (C unsigned semantics on a Java int). The C macro `ANG5 = ANG90/18` lifted to a class constant since values are module-stable. |
| p_pspr.c    | `doom.play.PPspr`     | Standard | Verified | 879 / ~. Full port: P_SetPsprite + P_CalcSwing + P_BringUpWeapon + P_CheckAmmo (with the C's preference ladder for backup-weapon picks) + P_FireWeapon + P_DropWeapon + the 18 A_* action callbacks (WeaponReady / ReFire / CheckReload / Lower / Raise / GunFlash / Punch / Saw / FireMissile / FireBFG / FirePlasma / FirePistol / FireShotgun / FireShotgun2 / FireCGun / Light0/1/2 / BFGSpray / BFGsound) + P_BulletSlope + P_GunShot + P_SetupPsprites + P_MovePsprites. New `Pspdef` struct (pspdef_t). Player gains `psprites[NUMPSPRITES=2]`. State extended with `actionPsp` (BiConsumer<Player, Pspdef>) for the C union's acp2 slot — see deviation 25; the InfoData regenerator must wire each weapon-state row's actionPsp before A_* callbacks fire from the state machine. The A_FireCGun pointer-diff `weaponinfo[].flashstate + psp->state - &states[S_CHAIN1]` becomes a linear `stateIndex(State)` lookup. PEnemy stub (pNoiseAlert no-op) so P_FireWeapon can dispatch. |
| p_enemy.c   | `doom.play.PEnemy`    | Standard | Verified | 2008 / ~. Full port: AI navigation infrastructure (P_RecursiveSound, P_NoiseAlert, P_CheckMeleeRange, P_CheckMissileRange, P_Move, P_TryWalk, P_NewChaseDir, P_LookForPlayers — with documented invariant that at least one playeringame[] seat must be true), entry callbacks (A_Look, A_Chase, A_FaceTarget, A_Fall, A_KeenDie), and 38 per-monster attack/death callbacks: A_PosAttack/A_SPosAttack/A_CPosAttack/A_CPosRefire/A_SpidRefire (zombie hitscan) + A_BspiAttack (arachnotron) + A_TroopAttack/A_SargAttack/A_HeadAttack/A_BruisAttack (melee+missile) + A_CyberAttack (rocket) + A_SkelMissile/A_Tracer/A_SkelWhoosh/A_SkelFist (revenant) + PIT_VileCheck/A_VileChase/A_VileStart/A_StartFire/A_FireCrackle/A_Fire/A_VileTarget/A_VileAttack (archvile resurrect + hellfire) + A_FatRaise/A_FatAttack1-3 (mancubus volleys) + A_SkullAttack/A_PainShootSkull/A_PainAttack/A_PainDie (lost soul + pain ele) + A_Scream/A_XScream/A_Pain/A_Explode (death helpers) + A_BossDeath/A_Hoof/A_Metal/A_BabyMetal (per-episode boss triggers + footsteps) + A_OpenShotgun2/A_LoadShotgun2/A_CloseShotgun2 (super shotgun, player-pspr signature) + A_BrainAwake/A_BrainPain/A_BrainScream/A_BrainExplode/A_BrainDie/A_BrainSpit/A_SpawnSound/A_SpawnFly (Romero head + cube spawn) + A_PlayerScream. Doomstat picks up `fastparm` + `gameepisode`. PTick picks up `gametic`. The C `static int easy = 0;` in A_BrainSpit lifts to a class-level alternation flag. The C `(divline_t*)bsp` reinterpret no longer needed here. The C `P_SpawnMobj(actor->target->x, actor->target->x, ...)` typo (second arg should be y) in A_VileTarget preserved verbatim per CLAUDE.md §2 (deviation 29). |
| p_inter.c   | `doom.play.PInter`    | Standard | Verified | 918 / ~. Full port: P_GiveAmmo + P_GiveWeapon + P_GiveBody + P_GiveArmor + P_GiveCard + P_GivePower + P_TouchSpecialThing (30+ pickup-class sprite-id dispatcher) + P_KillMobj + P_DamageMobj. Doomdef picks up MAXHEALTH / BASETHRESHOLD / INVULNTICS / INVISTICS / INFRATICS / IRONTICS. Pickup messages from d_englsh.h live as PInter constants (deviation 10). PPspr stub gains pDropWeapon. ISystem stub gains iTactile (no-op — matches the linuxdoom build). AmMap stub gains amStop. The C `target->player - players` pointer-subtract becomes a `playerIndex(Player)` linear scan (same pattern as PFloor.sectorIndex / PSight.sectorIndex). |
| p_sight.c   | `doom.play.PSight`    | Standard | Verified | 349 / ~. Full port: P_DivlineSide (with the C bug `if (x==node->y)` preserved verbatim — see deviation 27) + P_InterceptVector2 + P_CrossSubsector + P_CrossBSPNode + P_CheckSight. The C reinterprets `node_t*` → `divline_t*` (first four fields are x/y/dx/dy in both); Java cannot reinterpret, so a parallel `pDivlineSideNode(int, int, Node)` reads the four Node fields directly with identical arithmetic. `t1->subsector->sector - sectors` becomes a linear `sectorIndex(Sector)` scan (same pattern as PFloor). `validcount` snapshot lives on RMain. |
| p_spec.c    | `doom.play.PSpec`     | Standard | Partial | Sector-graph walkers (`getSide`/`getSector`/`twoSided`/`getNextSector`/`pFind{Lowest,Highest,Next}{Floor,Ceiling}Surrounding`/`pFindMinSurroundingLight`/`pFindSectorFromLineTag`) + `Result` enum + `FLOORSPEED` + animated textures (`Anim`/`AnimDef`, `animdefs[22]`, `P_InitPicAnims`) + per-tic `P_UpdateSpecials` (animation, line-scroll 48, button countdown — level timer `G_ExitLevel` deferred, deviation 23) + level-start `P_SpawnSpecials` (sector specials 1/2/3/4/8/9/10/12/13/14/17 + line 48 scrolls + -avg/-timer parsing) + `EV_DoDonut` (with the famous `(!flags & ML_TWOSIDED)` precedence-bug branch preserved verbatim — see deviation 24) + `P_CrossSpecialLine` (60+ case walk-trigger dispatcher with monster filter and projectile early-return) + `P_ShootSpecialLine` (3-case impact dispatcher). `P_PlayerInSpecialSector` waits on player.powers[]/P_DamageMobj. |
| p_doors.c   | `doom.play.PDoors`    | Standard | Verified | 717 / ~. T_VerticalDoor + EV_DoDoor + EV_DoLockedDoor + EV_VerticalDoor + P_SpawnDoorCloseIn30 + P_SpawnDoorRaiseIn5Mins. vldoor_e (8 values) as int constants. EV_VerticalDoor / EV_DoLockedDoor read `Mobj.player.cards[]` for keycard locks (Player + Mobj.player added in this round); the message strings (PD_BLUEK / PD_REDK / PD_YELLOWK / PD_BLUEO / PD_REDO / PD_YELLOWO from d_englsh.h) live on Dstrings. The `#if 0` sliding-door block is skipped (dead C code). The C's redundant `if (!p) return 0;` checks inside switch cases and the unused `secnum` local in EV_VerticalDoor are preserved verbatim (CLAUDE.md §2). |
| p_ceilng.c  | `doom.play.PCeilng`   | Standard | Verified | 279 / ~. Full port: T_MoveCeiling + EV_DoCeiling + active-ceiling slot table (MAXCEILINGS=30) + EV_CeilingCrushStop + P_ActivateInStasisCeiling. Sound calls go through SSound stub; `acv=NULL` pause sentinel maps to `acp1=null` (deviation 14). |
| p_floor.c   | `doom.play.PFloor`    | Standard | Verified | 555 / ~. Full port: `T_MovePlane` (generic floor/ceiling mover), `T_MoveFloor` (per-tic floor thinker), `EV_DoFloor` (13-way `floor_e` dispatch), `EV_BuildStairs` (`build8`/`turbo16`). `floormove_t` is the `FloorMove extends Thinker` inner class. Sector-pointer-difference (`tsec - sectors`) becomes a linear-scan `sectorIndex(Sector)` helper. The donutRaise dispatch lives in p_spec.c (deferred — only the per-tic completion path lives here). |
| p_lights.c  | `doom.play.PLights`   | Standard | Verified | 357 / ~. Five sector-lighting thinker subtypes (`FireFlicker`/`LightFlash`/`Strobe`/`Glow`) subclass `Thinker`; `acp1` bound via method reference. Z_Malloc elided for extended thinkers — they're Java objects (deviation 16). |
| p_plats.c   | `doom.play.PPlats`    | Standard | Verified | 295 / ~. Full port: T_PlatRaise + EV_DoPlat + activeplats[MAXPLATS] slot table + EV_StopPlat + P_ActivateInStasis + add/remove. plat_e and plattype_e as int constants (same rationale as PCeilng's ceiling_e). raise{AndChange,ToNearestAndChange} reads Side.sector.floorpic. |
| p_switch.c  | `doom.play.PSwitch`   | Standard | Verified | 655 / ~. Button/switch infrastructure (`alphSwitchList[]`, `P_InitSwitchList`, `P_StartButton`, `P_ChangeSwitchTexture`) plus the full `P_UseSpecialLine` line-special "use" dispatcher (50+ case labels routing to EV_VerticalDoor / EV_DoLockedDoor / EV_DoFloor / EV_DoCeiling / EV_DoDoor / EV_DoPlat / EV_BuildStairs / EV_DoDonut / EV_LightTurnOn / G_ExitLevel / G_SecretExitLevel). Preserves the C quirk `buttonlist->soundorg` (always slot-0 for switch SFX). |
| p_telept.c  | `doom.play.PTelept`   | Standard | Verified | 132 / ~. Full port of EV_Teleport: thinker-chain walk for an MT_TELEPORTMAN in the tag-matched sector, P_TeleportMove relocation, source/destination MT_TFOG spawn with sfx_telept, 18-tic player reactiontime gate, and angle/momentum reset. The C `sector - sectors` pointer-diff becomes a linear `sectorIndex(Sector)` scan (same pattern as PFloor / PSight). |
| p_saveg.c   | `doom.play.PSaveg`    | Standard | Verified | 586 / ~. Full save/load: P_ArchivePlayers / P_ArchiveWorld / P_ArchiveThinkers / P_ArchiveSpecials and the four Un* counterparts. New top-level orchestrators `save()` / `load(byte[])` wrap a ByteArrayOutputStream/InputStream + Data*Stream pair (replaces the C `byte* save_p` global with a per-call DataOutputStream / DataInputStream — see deviation 30). Field-level binary serialization replaces the C's struct-memcpy-with-pointer-swizzle pattern (which Java cannot reproduce). Thinker dispatch uses `instanceof` against the Java subclass instead of method-pointer comparisons. The known C bug (FireFlicker thinkers don't survive a save) is preserved. Stasis-paused ceilings/plats round-trip via a leading bool flag indicating whether `function.acp1` was non-null. |

## 5 — Sound

| C file      | Java class           | Tier     | Status  | Notes                              |
| ----------- | -------------------- | -------- | ------- | ---------------------------------- |
| s_sound.c   | `doom.sound.SSound`  | Standard | Verified | 879 / ~. Full mixer wrapper. sInit allocates the 16-channel table + clears the per-sfx cache. sStart kills any in-flight SFX and selects the per-map music (Doom 2: mus_runnin+gamemap-1; Doom 1: mus_e1m1 base offset by episode/map, E4 picks from the spmus[] curated table). sStartSoundAtVolume + sStartSound: priority + pitch perturb (sawup..sawhit ±8 range, others ±16, except itemup/tink which stay neutral), distance + stereo attenuation via sAdjustSoundParams (Graphics-Gems approx-Euclidean fixed-point distance + listener angle → sep), and sGetChannel pick (free-slot → same-origin override → priority kickout). sUpdateSounds re-runs the per-tic audibility re-check and feeds new vol/sep/pitch to ISound.iUpdateSoundParams. sChangeMusic / sStartMusic / sStopMusic dispatch through ISound.iRegisterSong / iPlaySong / iStopSong (stubs until MUS→MIDI lands). sPauseSound / sResumeSound bridge the BTS_PAUSE special-button latch. C `void* origin` → Java `Object` with instanceof downcast for Mobj / Degenmobj (Sector.soundorg) — see deviation 37. C function-pointer `S_AdjustSoundParams` out-params → `int[1]` holders for vol/sep/pitch. C `>=` priority comparison in sGetChannel preserved verbatim despite the upside-down "kick lower priority" semantics (CLAUDE.md §2). |
| i_sound.c   | `doom.sound.ISound`  | Hard     | Verified | 985 / ~. JavaFX audio backend — rewires the C's Linux DMX / SNDSERV glue onto javax.sound.sampled (SFX) + javax.sound.midi (music). iInitSound opens a 22050 Hz / 16-bit / stereo `SourceDataLine`; iInitMusic opens a `javax.sound.midi.Sequencer` + `Synthesizer` and bridges them via a Receiver chain. iSetChannels allocates the per-channel mixer table (NUM_CHANNELS = 16). iGetSfxLumpNum maps Sfxinfo→"DS<name>" WAD lookup. iStartSound lazy-decodes the DMX-format lump, caches the DmxBlob on Sfxinfo.data, picks the first free channel (or kicks the oldest by handle). iSubmitSound mixes all active channels once per tic (SAMPLES_PER_TIC = 630 frames) — 8-bit unsigned → signed 16-bit conversion + integer-saturating add — and writes the interleaved L/R buffer to the SourceDataLine (non-blocking; drops frames if the buffer is full). Music dispatch: iRegisterSong runs the lump through `doom.sound.MusToMidi`, loads the result into the Sequencer; iPlaySong sets the sequence + arms loop count + starts; iStopSong / iPauseSong / iResumeSong toggle the Sequencer transport; iSetMusicVolume broadcasts CC 7 (channel volume) on every MIDI channel through the Synth receiver. Catches `IOException | ISystem.DoomError` on lump load so unit tests without a WAD don't crash — deviation 34. Architecturally diverges from the C (deviation 36): the C does software mixing into a DMX buffer pumped by UDP socket; the Java port mixes inline + hands a single shared SourceDataLine + Sequencer. |
| mus2midi    | `doom.sound.MusToMidi` | Hard   | Verified | New module (no direct C equivalent — the Linux DMX driver consumed MUS natively). Converts a Doom MUS lump (binary format from the DMX library) into a Format-0 single-track MIDI byte stream consumable by `javax.sound.midi.MidiSystem.getSequence`. MUS header parse → score loop dispatching by MUS event type: release-note (→ MIDI Note Off), play-note (→ Note On, with the "high-bit volume" sub-mode reusing the per-channel last-volume cache), pitch bend (8-bit MUS bend → 14-bit MIDI bend), system event (MUS controllers 10..14 → MIDI CC 120..127), change controller (MUS controller 0 → MIDI Program Change; others → MIDI CC 1/7/10/11/91/93/64/67), end-of-score → MIDI End of Track meta. MUS channel 15 is swapped with channel 9 to map onto the GM percussion channel. MIDI division = 70 ticks per quarter (DMX MUS is ~140 Hz). VLQ delta-times are decoded from the MUS "last bit" trailing payload and re-encoded MIDI-style. |
| sounds.c    | `doom.sound.Sounds`  | Trivial  | Verified | 228 / ~. `sfxenum_t`/`musicenum_t` as `public static final int` constants; `S_sfx[sfx_chgun].link` self-reference patched up after array fill (see deviation 17). |

## 6 — Game, menu, HUD, finale

| C file      | Java class             | Tier     | Status  |
| ----------- | ---------------------- | -------- | ------- |
| g_game.c    | `doom.system.GGame`    | Standard | Partial | 1690 / ~. Input → ticcmd → playsim wired end-to-end + level-cycle. G_Responder + G_BuildTiccmd + G_Ticker (with full gameaction transition switch). gInitNew(skill, episode, map) queues ga_loadlevel; gDoLoadLevel sets per-episode sky, flips dead seats to PST_REBORN, resets per-player frags, calls PSetup.pSetupLevel. gDoCompleted advances gamemap (with Doom 1 / Doom 2 episode-end handling) and re-queues ga_loadlevel — when wi_stuff ports it inserts intermission. Player crosses an exit linedef → PSpec.pCrossSpecialLine → GGame.gExitLevel sets gameaction=ga_completed; next gTicker pumps the transition; engine cycles into the next map automatically. The mouse / joystick / chainsaw-override / chat-char / double-click / consistancy / netgame / demo branches stay deferred. New keymap defaults: arrows for movement, comma/period for strafe, RCTRL for fire, SPACE for use, RALT for strafe modifier, RSHIFT for run. PSetup gains pSetupLevel(episode, map, mask, skill) — orchestrates pInitThinkers + lump-name lookup + pSetupLevelGeometry + pLoadThings + pSpawnSpecials + per-player counter reset. |
| d_main.c    | `doom.system.DMain`    | Standard | Verified | 1171 / ~. Boot orchestrator + per-tic frame driver + title-screen demo loop. dDoomMain(args) parses -iwad / -file / -warp / -skill / -episode / -nomonsters / -respawn / -fast / -altdeath / -deathmatch, boots V_Init / Z_Init / W_InitMultipleFiles / R_InitData / R_Init / P_Init (PSwitch.pInitSwitchList + PSpec.pInitPicAnims + RThings.rInitSprites) / I_Init / S_Init / HU_Init / ST_Init / M_Init, then on -warp loads the requested level via PSetup.pSetupLevel; without -warp falls through to dStartTitle. dDoomLoop runs one frame: I_StartFrame → I_StartTic → dProcessEvents → drain advancedemo → gTicker → dDisplay → iUpdateSound + iSubmitSound. dDisplay dispatches by Doomstat.gamestate (GS_LEVEL → BSP + status bar + HUD; GS_INTERMISSION → wiDrawer; GS_FINALE → fDrawer; GS_DEMOSCREEN → dPageDrawer) and paints mDrawer on top. Title-screen loop (dStartTitle / dAdvanceDemo / dDoAdvanceDemo / dPageTicker / dPageDrawer): walks the TITLEPIC → demo1 → CREDIT → demo2 → TITLEPIC/HELP2 → demo3 → [retail] demo4 cycle, page-only sequences resolve into vDrawPatch of the named lump; demo-playback sequences re-arm advancedemo so the loop skips straight past them (G_DeferedPlayDemo deferred). dProcessEvents runs the full responder chain (M_Responder → F_Responder during GS_FINALE → AM_Responder → ST_Responder → G_Responder). The screen-wipe machinery (FWipe is Verified; D_Display integration with wipegamestate flipping) lands when the title→game transition wants a melt. |
| d_net.c     | `doom.system.DNet`     | Standard | Pending |
| m_menu.c    | `doom.ui.MMenu`        | Standard | Verified | 1893 / ~. Full in-game menu system: MainDef → {NewDef (skill picker) → EpiDef (Doom 1 episode), OptionsDef → SoundDef, LoadDef, SaveDef, ReadDef1/2 (HELP / CREDIT pages), QuitDOOM}. mResponder drives navigation (UP/DOWN/LEFT/RIGHT/ENTER/ESC/BACKSPACE + alphakey jump), the save-string text-entry mode, and the message-popup confirmation prompts (NIGHTMARE / ENDGAME / QSPROMPT / QLPROMPT / SWSTRING / NEWGAME / SAVEDEAD / LOADNET / NETEND / QLOADNET / QSAVESPOT). F-key shortcuts work even with menu closed: F1=help, F2=save, F3=load, F4=sound, F5=detail, F6=quicksave, F7=endgame, F8=messages, F9=quickload, F10=quit-DOOM (with random quitsounds[]/quitsounds2[] snippet), F11=gamma-cycle, +/-=screen-size. mDrawer paints the active Menu via its patch-name MenuItem list, animated M_SKULL1/M_SKULL2 cursor at 8-tic interval, and routes long messages through mWriteText with newline wrapping. Save/load file I/O implemented via new GGame.gSaveGame / gLoadGame / gDoSaveGame / gDoLoadGame which wrap PSaveg.save / load with a length-prefixed description header (Java port deviation from the C raw format — same shape as deviation 30 for the playsim body itself). The C function-pointer `messageRoutine` translates as Java `IntConsumer` (deviation 31); MenuItem callbacks become `IntConsumer` method references. mInit collapses the ReadThis slot for Doom 2 (single help page) and clamps the episode menu to 3 items for shareware / registered. Doomdef gained KEY_F1..F11 + KEY_MINUS / KEY_EQUALS. Doomstat gained `usergame` flag (set by gDoLoadLevel; menu uses it to gate save/end-game). SSound gained `snd_SfxVolume` / `snd_MusicVolume` + sSet*Volume stubs. Dstrings gained PRESSKEY / PRESSYN / LOADNET / QLOADNET / QSAVESPOT / SAVEDEAD / QSPROMPT / QLPROMPT / NEWGAME / NIGHTMARE / SWSTRING / MSGOFF / MSGON / NETEND / ENDGAME / DOSY / DETAILHI / DETAILLO / GAMMALVL0..4 / EMPTYSTRING. Mouse / joystick navigation paths deferred — single-keyboard port. R_SetViewSize hook left inert until detail-mode rendering ports. Wired into DMain.dProcessEvents (mResponder ahead of fResponder / amResponder / stResponder / gResponder), DMain.dDoomMain (mInit on boot), GGame.gTicker (mTicker each GS_LEVEL tic), DMain.dDisplay (mDrawer paints last so menu sits on top of view + status bar). |
| m_misc.c    | `doom.system.MMisc`    | Standard | Partial | 550 / ~. mWriteFile + mReadFile + the defaults-file load / save loop. mLoadDefaults seeds each known key with its baked-in default, then overlays the contents of `default.cfg` (or whatever `-config <path>` named); mSaveDefaults writes the same tab-separated text format back. Wired into `DMain.dDoomMain` (loaded right before the boolean cmdline-flag parse so explicit flags win) and `ISystem.iQuit` (saved on exit). Keys covered: mouse_sensitivity / sfx_volume / music_volume / show_messages / screenblocks / detaillevel / usegamma. Each Default entry pairs an IntSupplier getter + IntConsumer setter (deviation 38) so the underlying fields stay on their owning module classes (HuStuff / MMenu / SSound / VVideo) rather than getting collected onto MMisc as `public static int`. Key-bindings / mouse-button / joy-button / chat-macro defaults still deferred (the C arms each with a `int*` that the Java port doesn't have a use-site for yet). PCX screenshot writer + M_DrawText still deferred. |
| m_cheat.c   | `doom.play.MCheat`     | Trivial  | Verified | 101 / ~. `cheat_xlate_table` is `int[256]`; `cheatseq_t.p` uses sentinel `-1` to express C's NULL "not yet initialized". See deviation 9. |
| hu_lib.c    | `doom.ui.HuLib`        | Standard | Verified | 294 / ~. Text-line / scrolling / input widgets. `boolean*` "on" pointers translate as `boolean[]` holders (see deviation 19). `SHORT()` is a no-op since `Patch.width/height` are already parsed int. |
| hu_stuff.c  | `doom.ui.HuStuff`      | Standard | Partial | Pickup-message path: STCFN033..STCFN095 font load, huStart, huTicker (drains `player.message` into the SText widget, ticks the HU_MSGTIMEOUT counter), huDrawer, huErase. Wired into `DMain.dDoomMain` (huInit) + autostart (huStart) + dDisplay (huDrawer); `GGame.gTicker` pumps huTicker each tic. Chat widget, level-title banner, `shiftxform`, HU_Responder deferred (waits on m_menu / multiplayer chat). `huStart` has a Java-only `huFont[0]==null` early-return so playsim unit tests that exercise pSpawnPlayer without booting the engine don't crash — deviation 34. |
| st_lib.c    | `doom.ui.StLib`        | Standard | Verified | 262 / ~. Number/Percent/MultIcon/BinIcon widgets. `int*`/`boolean*` caller-owned holders translate as `int[1]`/`boolean[1]` (deviation 19). `SHORT()` is a no-op since Patch fields are pre-parsed int. BG=4 (status-bar layer) distinct from HuLib's BG=1. |
| st_stuff.c  | `doom.ui.StStuff`      | Standard | Verified | 1471 / ~. Full status-bar code: stInit loads tallnum / shortnum / STBAR / STARMS / STFBn / STKEYSn / STFST/TR/TL/OUCH/EVL/KILL/GOD0/DEAD0 face strip, allocates screens[4] as ST_WIDTH×ST_HEIGHT. stStart initialises Number/Percent/MultIcon/BinIcon widgets pointing at per-frame `int[1]`/`boolean[1]` views the C exposes via `&plyr->field` pointers; stUpdateWidgets refreshes those views each tic (deviation 35). stTicker advances stClock, samples M_Random for face-jitter, and bumps stUpdateFaceWidget through dead → evil-grin → ouch / turn → rampage → godface → straight priority ladder (with full unsigned `angle_t` comparison via `Integer.compareUnsigned` for the turn-face-toward-attacker branch). stDoPaletteStuff drives the damage / bonus / radiation palette shifts through `IVideo.iSetPalette` against the PLAYPAL sub-palettes. stDrawer dispatches stDoRefresh / stDiffDraw with full refresh-on-firsttime semantics. stResponder owns the cheat decoder — iddqd / idfa / idkfa / mus / noclip / idspispopd / idclip / behold[vsirial] / idchoppers / idmypos / idclev — feeds into player.message + cheats / weaponowned / cards / powers state. `(int *) &plyr->weaponowned[i+1]` boolean-to-int cast translates as `armsOwnedView[i][0] = plyr.weaponowned[i+1] ? 1 : 0;` (deviation 35). Wired into `DMain.dDoomMain` (stInit + autostart stStart), `DMain.dDisplay` (stDrawer), `DMain.dProcessEvents` (stResponder), `GGame.gTicker` (stTicker). Same Java-only `sbar==null` test guard as huStart (deviation 34). |
| wi_stuff.c  | `doom.ui.WiStuff`      | Standard | Partial | 1850 / ~. Single-player intermission. wiStart latches a Wbstartstruct snapshot (new `doom.ui.Wbstartstruct` class — port of wbstartstruct_t / wbplayerstruct_t), loads the per-episode WIMAPn / WILVnn / CWILVnn level-name patches + WINUMn / WIPCNT / WIF / WIENTER / WIOSTK/I/S / WISCRT2 / WITIME / WIPAR / WISUCKS / WIMINUS / WICOLON + WIA-prefix per-episode background animations + WIURH0/1 "You Are Here" + WISPLAT cleared-level dots. wiTicker drives the StatCount → ShowNextLoc → NoState state machine; sp_state counters tick kills/items/secrets % up at +2/tic (sfx_pistol cadence), then time + par at +3/tic (sfx_barexp on each transition); accelerate (BT_ATTACK/BT_USE edge-detected by wiCheckForAccelerate) skips to final values + sfx_sgcock + ShowNextLoc transition. wiDrawShowNextLoc paints the world-map background with WIA-prefix loop animations (Doom 1 only) + splat-on-cleared-levels + flashing "You Are Here". wiDrawStats lays out kills/items/secret %, time / par on a 3*lineHeight grid. Multiplayer paths (wiInitDeathmatchStats / wiInitNetgameStats and the dm_*/cnt_frags matrix) deferred until netgame ports. GGame.gDoCompleted rewritten to fill the shared wminfo (new `pars[4][10]` + `cpars[32]` par-time tables, secret-exit routing for Doom 1 maps 8/9 and Doom 2 maps 15/31), latch gamestate=GS_INTERMISSION, and call wiStart. ga_worlddone branch in gTicker writes wminfo.next back onto gameepisode/gamemap and fires Doom 2 post-map cutscenes (fStartFinale) for maps 6/11/15/20/30/31. Same Java-only `WAD-load failure → ga_worlddone fallback` guard as huStart / stStart (deviation 34): test harnesses without a WAD don't crash on WIMAPn lookup. Player gains `didsecret` boolean. |
| am_map.c    | `doom.ui.AmMap`        | Standard | Verified | 1349 / ~. Full vector-line overhead automap. amStart caches per-level state (lastlevel + lastepisode) so re-entering the automap on the same map skips amLevelInit's AABB sweep over PSetup.vertexes. amTicker drives follow-player tracking, zoom (M_ZOOMIN / M_ZOOMOUT per-tic multipliers), and bounded panning. amDrawer paints into screens[0]: amClearFB → optional grid (MAPBLOCKUNITS-aligned, gridded against PSetup.bmaporgx/y) → amDrawWalls (picks WALLCOLORS / FDWALLCOLORS / CDWALLCOLORS / TSWALLCOLORS / SECRETWALLCOLORS based on linedef flags + front/back-sector floor/ceiling deltas, ML_DONTDRAW filtered unless cheating) → amDrawPlayers (7-segment player_arrow vector character rotated through Tables.finesine/finecosine, or the 16-segment cheat_player_arrow with the "ddt" marker in iddt cheat mode) → amDrawThings (cheating==2 only, thintriangle_guy per mobj in Sector.thinglist chains) → amDrawCrosshair → amDrawMarks. amClipMline implements the Cohen-Sutherland clipper against the (f_x,f_y,f_w,f_h) window with the C `DOOUTCODE` macro inlined as a helper. amDrawFline is the C's Bresenham loop verbatim. amResponder owns the TAB toggle + arrow-pan + +/- zoom + 0 big-screen + F follow-toggle + G grid-toggle + M mark + C clear-marks + iddt cheat-cycle keys. Wired into DMain.dProcessEvents (eats input ahead of stResponder when active), GGame.gTicker (amTicker each GS_LEVEL tic), DMain.dDisplay (amDrawer replaces rRenderPlayerView when active, status bar still paints). Same Java-only `WAD-load failure` guard on amLoadPics so AMMNUM0..9 marks degrade gracefully on shareware WADs that omit them (deviation 34). Doomdef gained KEY_TAB. |
| f_finale.c  | `doom.ui.FFinale`      | Standard | Verified | 738 / ~. Full end-of-episode finale + Doom 2 cast call. fStartFinale flips gamestate→GS_FINALE, picks per-episode / per-map text + flat (Doom 1 E1–E4 vs Doom 2 maps 6/11/15/20/30/31), kicks mus_victor / mus_read_m. fTicker types the text one char every TEXTSPEED tics then sits TEXTWAIT tics on the static art (E1→HELP2, E2→VICTORY2, E3→fBunnyScroll PFUB1/2 scroll + END0-6 stamp, E4→ENDPIC, retail-E1→CREDIT). Doom 2 fTicker checks for any player button after the 50-tic intro and flips gameaction→ga_worlddone (next map) or fStartCast on map 30. Cast call cycles all 17 monsters: see-state walk → 12-frame mark hits melee/missile attack → 24-frame mark or returning to seestate ends attack; key press forces deathstate. The C `goto stopattack` translates as a labelled-flag + helper method (`stopAttack()`). Dispatch wired through DMain.dDisplay (GS_FINALE → fDrawer), GGame.gTicker (GS_FINALE → fTicker), DMain.dProcessEvents (fResponder ahead of stResponder during GS_FINALE), GGame.gTicker's ga_victory→fStartFinale and ga_worlddone→ga_loadlevel handlers. Doomstat gained `gamestate` / `viewactive` / `wipegamestate` + GS_LEVEL/INTERMISSION/FINALE/DEMOSCREEN constants. Dstrings gained E1TEXT..E4TEXT, C1TEXT..C6TEXT, CC_* names. SSound.sStartMusic stub for the E3 bunny music kick-in. |
| f_wipe.c    | `doom.ui.FWipe`        | Standard | Verified | 302 / ~. Melt+ColorXForm screen wipes. `short*` aliasing of the `byte[]` framebuffers is translated as 2-byte-pair copies (see deviation 12); `I_ReadScreen` lands in a new `doom.ui.IVideo` stub. |
| dstrings.c  | `doom.system.Dstrings` | Trivial  | Verified | 72 / ~. `endmsg[]` preserves the two missing-comma string concatenations from the C (see deviation 10). d_englsh.h constants ported into callers as they land. |
| info.c      | `doom.play.Info`       | Trivial  | Verified | Three enums from info.h as `public static final int` constants (deviation 17): `spritenum_t` (138 entries), `statenum_t` (967), `mobjtype_t` (137). Plus the three info.c data tables (sprnames[138], states[967], mobjinfo[137]) extracted by `scripts/extract_info_data.py` into the autogenerated `InfoData.java`. State.action / actionPsp now bind to PMobj/PPspr/PEnemy method-references for every A_* in info.c. The state machine fires the bound callbacks during P_SetMobjState / P_SetPsprite — monsters chase, weapons fire, everything that was previously inert from state transitions is now live. See deviation 25 for the union-→-two-fields mapping. |
| doomdef.c   | `doom.system.Doomdef`  | Trivial  | Verified | No executable content in C (just includes). `Doomdef.java` holds the screen/timing constants lower modules need; enums/keys added as callers land. |
| doomstat.c  | `doom.system.Doomstat` | Trivial  | Partial | Stub: `gamemode`, `demoplayback` only; other cross-module globals added as dependents land. |
| d_items.c   | `doom.play.DItems`     | Trivial  | Verified | 138 / ~. `weaponinfo[NUMWEAPONS]` static table indexed by `weapontype_t`. `weapontype_t` and `ammotype_t` (doomdef.h) lifted as `public static final int` constants on Doomdef. Each `WeaponInfo` is an immutable nested record; state IDs come from `Info`. |

## 7 — Platform adapters (last — JavaFX shell)

| C file        | Java class               | Tier | Status  | Notes                                              |
| ------------- | ------------------------ | ---- | ------- | -------------------------------------------------- |
| i_main.c      | `doom.ui.JfxDoomApp`     | Hard | Partial | Replaces i_main.c + the audiovisual halves of i_video.c / i_sound.c. JavaFX `Application` subclass; `main(args)` launches into `start(Stage)` which builds a 320×200×SCALE Canvas + `WritableImage` backed by a `PixelBuffer<IntBuffer>`, registers `IVideo.finishUpdateHook` (palette-byte → ARGB int conversion + Canvas blit), `IVideo.setPaletteHook` (PLAYPAL upload), `IVideo.startTicHook` (key-event drain — no-op until M_Responder / G_Responder ports), and starts an `AnimationTimer` that drives `DMain.dDoomLoop()` at TICRATE Hz. After `DMain.dDoomMain(launchArgs)` boots the engine, the per-frame loop ticks the playsim, walks the BSP, and pushes pixels onto the screen. ESC closes the window. Run with `gradle run --args="-iwad reference-wads/DOOM.WAD -warp 1 1"`. |
| i_system.c    | `doom.system.ISystem`    | Standard | Verified | 183 / ~. Full port: I_Error (typed DoomError throw), I_Tactile (no-op, matches Linux build), I_BaseTiccmd, I_GetHeapSize / I_ZoneBase (Java port has no slab — returns 0-length byte[]; ZZone uses JVM heap), I_GetTime (System.nanoTime()-based, returns 1/70th-second tics since first call), I_Init / I_Quit (dispatches into ISound / DNet / IVideo stubs — full bodies land with the JavaFX shell), I_WaitVBL (Thread.sleep), I_BeginRead / I_EndRead (no-ops, match Linux build), I_AllocLow (zero-init byte[]). |
| i_video.c     | `doom.ui.IVideo`         | Hard | Partial | Stubs for the JavaFX shell hooks: I_InitGraphics / I_ShutdownGraphics / I_StartFrame / I_StartTic / I_UpdateNoBlit / I_FinishUpdate / I_SetPalette. I_ReadScreen (used by the screen-wipe path) is full. The actual JavaFX shell (PixelBuffer<IntBuffer> + Canvas + Scene event hooks) replaces these stubs in place when JfxDoomApp ports. |
| i_net.c       | `doom.system.INet`       | Standard | Pending | Single-player stub first.                          |
| d_net.c       | `doom.system.DNet`       | Standard | Partial | Single-player stub: D_QuitNetGame / NetUpdate / TryRunTics no-op. Full ticcmd-exchange protocol lands when multiplayer comes online. |

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
25. `info.c` data tables — A_* action callbacks are null:
    - The C states[] table sets each entry's `action.acp1` (mobj-AI
      callback) or `action.acp2` (weapon psprite callback) to one
      of the A_* function pointers. Java has no union; State carries
      both `action: Consumer<Mobj>` and `actionPsp: BiConsumer<Player,Pspdef>`,
      and only one is populated per row.
    - PPspr now provides every weapon-action A_* (A_Punch /
      A_FireMissile / A_WeaponReady / etc.) as a static method —
      callable directly. PEnemy still has only the pNoiseAlert
      stub; A_Look / A_Chase / A_Fire* land when p_enemy ports.
    - `InfoData.java` is autogenerated by
      `scripts/extract_info_data.py` and currently sets every
      action / actionPsp to null. Sprite frame transitions still
      work because P_SetMobjState / P_SetPsprite skip the dispatch
      when both fields are null. The regenerator pass that wires
      each row's binding (e.g. `(p, psp) -> PPspr.aFirePistol(p, psp)`)
      is a separate follow-up — once it runs, the A_* callbacks
      fire from the state machine instead of just being directly
      callable.
    - The 28 mobjflag_t bit constants live on `Mobj` (e.g.
      `Mobj.MF_SOLID`); the InfoData generator translates the C
      `MF_X` tokens to `Mobj.MF_X`.

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

38. m_misc.c defaults `int*` → IntSupplier / IntConsumer pair:
    - The C uses `default_t { char *name; int *location; int
      default; ... }` and reads / writes the live menu field
      through the location pointer.
    - Java has no equivalent. The port keeps the same table
      shape but the `int*` becomes a getter + setter method-
      reference pair (`IntSupplier` + `IntConsumer`). The
      underlying fields stay on their owning class (`HuStuff
      .showMessages`, `MMenu.mouseSensitivity`, ...) instead of
      being moved onto `MMisc` as `public static int`.
    - Boolean-typed defaults (showMessages) get an explicit
      coerce-to-int getter + coerce-from-int setter so the on-
      disk format stays `"name\t\tN\n"` regardless of the Java
      field type.

37. s_sound.c `void* origin` → `Object` listener with instanceof:
    - The C passes either a `mobj_t*` or a `degenmobj_t*` (a
      sector's `soundorg` trio of x/y/z) as the SFX origin via a
      `void*`. Both structs share an x/y/z prefix, so the C reads
      them through casts.
    - Java has no inheritance between Mobj and Degenmobj (the
      two classes live in different packages and don't share a
      base). The Java port types the parameter as `Object` and
      uses `instanceof` checks in `originX` / `originY` helpers
      to read x/y. PCeilng / PDoors / PPlats / PFloor pass
      `sector.soundorg`; PPspr / PMobj pass `mobj`. Same call
      shape as the C; just runtime-typed instead of pointer-cast.

36. i_sound.c JavaFX backend architecture:
    - The C `i_sound.c` is Linux DMX driver glue + a UDP-socket
      sound-server (`sndserver`) for out-of-process mixing. It
      reads PCM samples into a private buffer that the DMX kernel
      driver picks up.
    - Java has no DMX equivalent. The port replaces the entire
      backend with `javax.sound.sampled.SourceDataLine`: open a
      single 22050 Hz / 16-bit / stereo line, mix all 16 channels
      inline once per tic (SAMPLES_PER_TIC = 630 frames), write
      the interleaved L/R buffer non-blocking. SFX lumps are
      lazily DMX-decoded on first play and cached on
      `Sfxinfo.data` as `DmxBlob(samples, rate)`. Pitch
      perturbation lives on the SSound side via the same `pitch`
      field; the JavaFX mixer doesn't act on it (matches the C
      Linux build, which also ignores pitch).
    - The C `int handle` identifies a playing sound in the DMX
      buffer. The Java port keeps the same shape with a monotonic
      `nextHandle` counter; channels store their owning handle so
      `iStopSound(handle)` / `iSoundIsPlaying(handle)` / 
      `iUpdateSoundParams(handle, ...)` translate exactly. Music
      dispatch stays no-op until a MUS→MIDI converter ports.

35. st_stuff.c widget bool→int holders:
    - The C wires status-bar widgets to live game state with
      `STlib_initMultIcon(&w_arms[i], ..., (int *) &plyr->weaponowned[i+1], ...)`
      — casting the address of a `boolean` to an `int*` so the
      widget can re-read ownership every frame. Same trick for
      `&plyr->ammo[k]`, `&plyr->health`, `&plyr->armorpoints`,
      `&plyr->cards[i]`, `&plyr->readyweapon`.
    - Java has no `&boolean`. The Java port keeps a per-widget
      `int[1]` (or `boolean[1]`) "view" on `StStuff` —
      `armsOwnedView[6][1]`, `keyboxesView[3][1]`, `healthView[1]`,
      `armorView[1]`, `ammoView[4][1]`, `maxAmmoView[4][1]`,
      `readyAmmoView[1]`, `faceIndexView[1]`, `fragsCountView[1]`,
      `stStatusbarOn`, `stArmsOn`, `stFragsOn`, `stNotDeathmatch`
      — and `stUpdateWidgets` copies live player state into them
      each tic. The widget paint code reads `view[0]` and gets the
      current value, matching C semantics exactly.
    - Same convention as HuLib/StLib's caller-owned holders
      (deviation 19). The extra copy is one indirection per tic and
      keeps the same `[0]` access pattern the existing widget
      drawers already use.

34. hu_stuff.c `huStart` font-unloaded guard:
    - The C `HU_Start` assumes `HU_Init` already ran and the font
      patches are loaded — every code path in D_DoomMain calls
      HU_Init before any HU_Start can fire. Several playsim unit
      tests (PMobjTest.spawnPlayerWiresPlayerAndMobj, etc.) drive
      `P_SpawnPlayer` directly without booting the engine, so the
      font array is still all-null when pSpawnPlayer reaches the
      `HuStuff.huStart()` wake-up call → NPE in
      `HuLib.huLibInitSText` on `font[0].height`.
    - The Java port adds a one-line `if (huFont[0] == null) return;`
      early-return at the top of `huStart` (after the leading
      `huStop` so reentrant calls still reset). The check is
      unreachable in the real boot sequence; it only matters for
      partial-engine tests. Same spirit as PEnemy.pLookForPlayers's
      `playeringame[0]=true` test invariant — accommodating Java
      tests that skip the C's full init order.
    - HuStuff itself stays C-faithful: real-game behavior is
      unchanged because huFont[0] is always non-null after
      `DMain.dDoomMain` runs `HuStuff.huInit()`.

33. r_things.c sprite-rotate tri-state:
    - The C `spriteframe_t.rotate` is declared `boolean` but used
      as a tri-state (-1 = "no patches found", 0 = "single rotation
      for all views", 1 = "8 rotations required"). The C reads the
      sentinel via the `memset(sprtemp, -1, sizeof(sprtemp))` byte-
      fill which leaves rotate at 0xff (== -1 when read as int).
    - Java has no equivalent fill — the boolean stays a true/false
      pair. The Java port detects the "no patches" case explicitly
      by scanning lump[] for any non-(-1) entry, which the C doesn't
      need to do because the rotate sentinel encoded the same
      information. Same observable error for invalid sprites; just
      a different signal path.

32. r_segs.c `lastopening` aliasing pattern:
    - The C uses `lastopening` as a moving `short*` into the shared
      `openings[MAXOPENINGS]` buffer. R_StoreWallRange carves slices
      out of it for three things per drawseg: sprtopclip (top
      sprite-clip row), sprbottomclip (bottom sprite-clip row), and
      maskedtexturecol (per-column masked-texture x-offset). Each
      slice is anchored with a base-offset trick — e.g.
      `ds->maskedtexturecol = lastopening - rw_x;` so that
      `maskedtexturecol[col]` resolves to
      `openings[lastopening_at_alloc + (col - rw_x)]`. Reads /
      writes index by absolute screen-x; the negative bias makes
      the column ranges convenient.
    - Java has no pointer arithmetic. Each slice on Drawseg is a
      `(short[] base, int offset)` pair: `sprtopclip` /
      `sprtopclipOffset`, `sprbottomclip` / `sprbottomclipOffset`,
      `maskedtexturecol` / `maskedtexturecolOffset`. Reads do
      `base[offset + col]`. The same backing array (RPlane.openings)
      is shared; only the bookkeeping changes.
    - `memcpy` of clip-row slices becomes `System.arraycopy` of
      shorts (2 bytes per short in the C → 1 short[] entry in Java,
      same width).

31. Renderer `colfunc` / `spanfunc` function-pointer dispatch:
    - The C `r_main.c` keeps function pointers (`colfunc`,
      `basecolfunc`, `fuzzcolfunc`, `transcolfunc`, `spanfunc`)
      that R_ExecuteSetViewSize binds to `R_DrawColumn` /
      `R_DrawColumnLow` / `R_DrawSpan` / `R_DrawSpanLow` based on
      the detailshift. Hot paths (R_MapPlane, R_DrawPlanes,
      R_DrawSegLoop, R_DrawSprite) call through the pointer.
    - Java's first-class `Runnable` would model this faithfully,
      but the call sites are few (a couple per renderer module)
      and inlining `if (detailshift == 0) RDraw.rDrawSpan(); else
      RDraw.rDrawSpanLow();` is clearer to read and one less layer
      of indirection. The C does the function-pointer dance to
      avoid recompilation on resolution changes; Java doesn't have
      that constraint.
    - Per-call overhead is identical (one branch test on
      detailshift). The choice is purely readability. R_MapPlane
      and R_DrawPlanes both inline the dispatch.

30. `p_saveg.c` save format diverges from the C:
    - The C `P_Archive*` family memcpy's full struct contents into
      a contiguous byte buffer, then "swizzles" pointer fields
      (state_t* → S_* index, sector_t* → sector index,
      player_t* → 1-based seat). Java has no struct-as-bytes view,
      so the port writes each field individually through
      DataOutputStream / DataInputStream.
    - Result: the on-disk save format does NOT match the C save
      file layout. Saves from the original DOS Doom can't be loaded
      by the Java port and vice-versa. This is acceptable because
      (a) the C save format isn't a documented wire format, just a
      memory snapshot dependent on struct padding / pointer width,
      and (b) demo playback (`.lmp`) uses a different format
      handled by g_game.c, so demo compatibility is unaffected.
    - The C `byte* save_p` global becomes a per-call
      DataOutputStream (`PSaveg.out`) or DataInputStream
      (`PSaveg.in`); single-flight, mirroring the C invariant.
    - The C `PADSAVEP()` 4-byte-alignment macro is dropped — Data*
      Stream's per-field framing makes alignment irrelevant.
    - Thinker dispatch uses `instanceof` against the Java subclass
      (Mobj, Ceiling, VLDoor, FloorMove, Plat, LightFlash, Strobe,
      Glow) instead of comparing `function.acp1` to the action
      method-reference. Method references in Java don't have
      reliable equality, and the type-discriminated dispatch is
      the natural Java idiom.
    - Known C omission preserved: FireFlicker thinkers (from
      p_lights.c) have no archive case in C and so don't survive a
      save. The Java port matches this verbatim.

29. `p_enemy.c` A_VileTarget x/y typo:
    - The C calls `P_SpawnMobj(actor->target->x, actor->target->x,
      actor->target->z, MT_FIRE)` — the second argument should be
      `actor->target->y` but is `actor->target->x`. As a result the
      Vile's hellfire mobj spawns on the line y = x (in fixed-point
      world coordinates) rather than at the target's actual position,
      then `A_Fire` repositions it 24 units in front of the target
      every tic. The visual artifact (hellfire briefly appearing at
      a wrong spot on first frame before snapping into place) is
      well-documented Doom lore. Per CLAUDE.md §2 the Java port
      preserves the typo verbatim — call site comment flags it.

28. `p_map.c` P_RadiusAttack double-shift:
    - The C computes `dist = (damage+MAXRADIUS)<<FRACBITS`. But
      `MAXRADIUS` is already in fixed_t units (`32*FRACUNIT`), so
      the shift effectively double-counts the radius. The result is
      a much larger blockmap iteration window than intended; per-thing
      filtering inside PIT_RadiusAttack still applies the correct
      Chebyshev distance check, so the explosion's actual damage
      footprint is unchanged. Per CLAUDE.md §2 the Java port
      preserves the double shift verbatim — both because demo
      compatibility cares and because the cost is just a slightly
      wider blockmap sweep.

27. `p_sight.c` P_DivlineSide quirk + node-as-divline reinterpret:
    - The horizontal-axis branch in P_DivlineSide reads
      `if (x==node->y)` (note `y`, not `x`) — a likely C typo since
      the test should presumably mirror the vertical branch's
      `if (x==node->x)`. Per CLAUDE.md §2 the Java port preserves
      the misspelled comparison verbatim. The branch is rarely
      live (it requires x==y simultaneously plus dy==0) so the bug
      almost never affects sight outcomes; preserved for parity.
    - The C calls `P_DivlineSide(strace.x, strace.y, (divline_t*)bsp)`
      to reinterpret the first four fields of node_t (x/y/dx/dy)
      as a divline_t. Java cannot punning-cast, so the Java port
      adds `pDivlineSideNode(x, y, Node)` with identical arithmetic
      reading the four Node fields directly. Same numeric output
      across the full input domain (the unit test exhaustively
      verifies it for a 17×17 grid).

26. `p_setup.c` deathmatchstarts pool:
    - The C declares `mapthing_t deathmatchstarts[10]` plus a moving
      pointer `mapthing_t* deathmatch_p` that P_SpawnMapThing bumps
      and G_DeathMatchSpawnPlayer reads as the upper bound. Java has
      no pointer-into-array type that survives reseating; the port
      stores the upper bound as `int deathmatchPIndex` (logical
      index, equivalent to `deathmatch_p - deathmatchstarts`) and
      reads/writes `deathmatchstarts[deathmatchPIndex]`. Same
      observable contract; G_DeathMatchSpawnPlayer (when ported)
      iterates `0..deathmatchPIndex-1`.
    - The slot stores a `mthing.copy()` rather than the source
      reference, since the C `memcpy(deathmatch_p, mthing, sizeof(*mthing))`
      makes a full value copy. P_LoadThings discards each Mapthing
      after dispatch, so failing to copy would alias every slot to
      a no-longer-tracked input record.
    - P_SpawnMapThing also `mthing.copy()`s into Mobj.spawnpoint and
      into PSetup.playerstarts[], for the same reason.

21. `p_map.c` + `p_floor.c`:
    - `P_ChangeSector` now has the full body (PIT_ChangeSector
      iteration, crush damage, dropped-item removal, gibs state).
      The legacy stub-return survives as `PMap.changeSectorOverride`
      + `PMap.changeSectorResult`, only enabled by tests: when the
      override flag is true, `pChangeSector` returns
      `changeSectorResult` directly, skipping the real iteration.
      Plane-mover tests (PCeilng, PDoors, PFloor, PPlats) set the
      override in their `@BeforeEach` so they can drive the crush
      path without populating a full mobj-bearing world. Production
      callers leave `changeSectorOverride = false` (the default).
    - `result_e` (`ok`/`crushed`/`pastdest`) lives on `PSpec` as a Java
      enum — ordinals match the C enum (0/1/2). `FLOORSPEED` also lives
      there since the relevant #defines are in p_spec.h.
    - Only T_MovePlane is ported from p_floor.c; T_MoveFloor,
      EV_DoFloor, and EV_BuildStairs reach into yet-to-port helpers
      and land with the fuller p_spec port.
