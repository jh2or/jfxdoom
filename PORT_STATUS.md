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
| r_main.c    | `doom.render.RMain`        | Hard | Partial | BSP descent (`R_PointOnSide` + `R_PointOnSegSide` + `R_PointInSubsector`) + angle/distance helpers (`R_PointToAngle`, `R_PointToAngle2`, `R_PointToDist` + `viewx`/`viewy` globals) + `validcount` + the renderer scalars (`centery`, `centerx`, `extralight`) read by r_draw. The remaining view-setup / texture-mapping / lighting initializers (`R_InitTables`, `R_InitTextureMapping`, `R_InitLightTables`, `R_ExecuteSetViewSize`, `R_Init`, `R_SetupFrame`, `R_RenderPlayerView`, `R_ScaleFromGlobalAngle`) land with r_bsp/r_segs/r_things. |
| r_bsp.c     | `doom.render.RBsp`         | Hard | Pending |                                    |
| r_plane.c   | `doom.render.RPlane`       | Hard | Pending |                                    |
| r_segs.c    | `doom.render.RSegs`        | Hard | Pending | Pointer-arithmetic heavy.          |
| r_things.c  | `doom.render.RThings`      | Hard | Pending | Pointer-arithmetic heavy.          |
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
| s_sound.c   | `doom.sound.SSound`  | Standard | Partial | Stub: `sStartSound(Object, int)` as no-op so plane-movers (PCeilng, PDoors, PPlats, ...) can call it. Full mixer/attenuation/channel bookkeeping lands with i_sound. |
| i_sound.c   | `doom.sound.ISound`  | Hard     | Pending | JavaFX audio backend, do last.     |
| sounds.c    | `doom.sound.Sounds`  | Trivial  | Verified | 228 / ~. `sfxenum_t`/`musicenum_t` as `public static final int` constants; `S_sfx[sfx_chgun].link` self-reference patched up after array fill (see deviation 17). |

## 6 — Game, menu, HUD, finale

| C file      | Java class             | Tier     | Status  |
| ----------- | ---------------------- | -------- | ------- |
| g_game.c    | `doom.system.GGame`    | Standard | Pending |
| d_main.c    | `doom.system.DMain`    | Standard | Pending |
| d_net.c     | `doom.system.DNet`     | Standard | Pending |
| m_menu.c    | `doom.ui.MMenu`        | Standard | Partial | Stub: `menuactive` flag for p_tick.c P_Ticker's playsim-pause gate. Full menu (navigation, sliders, draw routine) lands later. |
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
| info.c      | `doom.play.Info`       | Trivial  | Verified | Three enums from info.h as `public static final int` constants (deviation 17): `spritenum_t` (138 entries), `statenum_t` (967), `mobjtype_t` (137). Plus the three info.c data tables (sprnames[138], states[967], mobjinfo[137]) extracted by `scripts/extract_info_data.py` into the autogenerated `InfoData.java`. State.action / actionPsp now bind to PMobj/PPspr/PEnemy method-references for every A_* in info.c. The state machine fires the bound callbacks during P_SetMobjState / P_SetPsprite — monsters chase, weapons fire, everything that was previously inert from state transitions is now live. See deviation 25 for the union-→-two-fields mapping. |
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
