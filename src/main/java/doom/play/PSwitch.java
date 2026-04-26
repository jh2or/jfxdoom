// Port of linuxdoom-1.10/p_switch.c — partial.
//
// Switch and button bookkeeping: the alphabetical switch table, the
// per-level `switchlist[]` / `buttonlist[]` state, P_InitSwitchList,
// P_StartButton, and P_ChangeSwitchTexture.
//
// P_UseSpecialLine — the giant use-action dispatcher — pulls in
// `EV_VerticalDoor` (needs Player.cards), `EV_BuildStairs` /
// `EV_DoFloor` / `EV_DoDonut` (p_floor.c is only partial),
// `G_ExitLevel` / `G_SecretExitLevel` (g_game not ported), and
// Mobj.player, so it lands as those dependencies do. All three EV_Do*
// actions the switch code triggers (doors, ceilings, plats) are
// already available, so P_UseSpecialLine will click into place once
// EV_VerticalDoor and the floor EV_* helpers are ready.
//
// The commented-out debug block inside P_InitSwitchList (the
// `R_CheckTextureNumForName` sanity probe guarded by `#if 0`) is
// intentionally skipped (CLAUDE.md §2 — dead C code stays dead).
package doom.play;

import doom.render.Line;
import doom.render.RData;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.Doomstat;
import doom.system.ISystem;

public final class PSwitch {

    // p_spec.h lines 240–247 — sizing / timing.
    public static final int MAXSWITCHES = 50;
    public static final int MAXBUTTONS  = 16;
    public static final int BUTTONTIME  = 35;

    // p_spec.h lines 218–224 — bwhere_e.
    // `bottom` collides with common identifiers; int constants keep
    // the switch labels straight and match ceiling_e / plat_e style.
    public static final int top    = 0;
    public static final int middle = 1;
    public static final int bottom = 2;

    // p_spec.h lines 209–215 — switchlist_t.
    public static final class SwitchDef {
        public final String name1;
        public final String name2;
        public final int episode;

        public SwitchDef(String name1, String name2, int episode) {
            this.name1 = name1;
            this.name2 = name2;
            this.episode = episode;
        }
    }

    // p_spec.h lines 227–235 — button_t.
    // `soundorg` is declared `mobj_t*` in the C but every assignment
    // casts from a `degenmobj_t*` (sector soundorg). Java keeps the
    // actual stored type, which is Degenmobj — SSound takes Object so
    // this doesn't constrain the call site.
    public static final class Button {
        public Line line;
        public int where;       // bwhere_e
        public int btexture;
        public int btimer;
        public Object soundorg;
    }

    // p_switch.c lines 48–97 — alphSwitchList.
    // Last entry is the C sentinel {"\0","\0",0} used as the
    // loop-terminator in P_InitSwitchList.
    public static final SwitchDef[] alphSwitchList = {
            // Doom shareware episode 1 switches
            new SwitchDef("SW1BRCOM",  "SW2BRCOM",  1),
            new SwitchDef("SW1BRN1",   "SW2BRN1",   1),
            new SwitchDef("SW1BRN2",   "SW2BRN2",   1),
            new SwitchDef("SW1BRNGN",  "SW2BRNGN",  1),
            new SwitchDef("SW1BROWN",  "SW2BROWN",  1),
            new SwitchDef("SW1COMM",   "SW2COMM",   1),
            new SwitchDef("SW1COMP",   "SW2COMP",   1),
            new SwitchDef("SW1DIRT",   "SW2DIRT",   1),
            new SwitchDef("SW1EXIT",   "SW2EXIT",   1),
            new SwitchDef("SW1GRAY",   "SW2GRAY",   1),
            new SwitchDef("SW1GRAY1",  "SW2GRAY1",  1),
            new SwitchDef("SW1METAL",  "SW2METAL",  1),
            new SwitchDef("SW1PIPE",   "SW2PIPE",   1),
            new SwitchDef("SW1SLAD",   "SW2SLAD",   1),
            new SwitchDef("SW1STARG",  "SW2STARG",  1),
            new SwitchDef("SW1STON1",  "SW2STON1",  1),
            new SwitchDef("SW1STON2",  "SW2STON2",  1),
            new SwitchDef("SW1STONE",  "SW2STONE",  1),
            new SwitchDef("SW1STRTN",  "SW2STRTN",  1),

            // Doom registered episodes 2&3 switches
            new SwitchDef("SW1BLUE",   "SW2BLUE",   2),
            new SwitchDef("SW1CMT",    "SW2CMT",    2),
            new SwitchDef("SW1GARG",   "SW2GARG",   2),
            new SwitchDef("SW1GSTON",  "SW2GSTON",  2),
            new SwitchDef("SW1HOT",    "SW2HOT",    2),
            new SwitchDef("SW1LION",   "SW2LION",   2),
            new SwitchDef("SW1SATYR",  "SW2SATYR",  2),
            new SwitchDef("SW1SKIN",   "SW2SKIN",   2),
            new SwitchDef("SW1VINE",   "SW2VINE",   2),
            new SwitchDef("SW1WOOD",   "SW2WOOD",   2),

            // Doom II switches
            new SwitchDef("SW1PANEL",  "SW2PANEL",  3),
            new SwitchDef("SW1ROCK",   "SW2ROCK",   3),
            new SwitchDef("SW1MET2",   "SW2MET2",   3),
            new SwitchDef("SW1WDMET",  "SW2WDMET",  3),
            new SwitchDef("SW1BRIK",   "SW2BRIK",   3),
            new SwitchDef("SW1MOD1",   "SW2MOD1",   3),
            new SwitchDef("SW1ZIM",    "SW2ZIM",    3),
            new SwitchDef("SW1STON6",  "SW2STON6",  3),
            new SwitchDef("SW1TEK",    "SW2TEK",    3),
            new SwitchDef("SW1MARB",   "SW2MARB",   3),
            new SwitchDef("SW1SKULL",  "SW2SKULL",  3),

            // Sentinel (episode == 0 → loop exits).
            new SwitchDef("\0", "\0", 0),
    };

    // p_switch.c lines 99–101 — runtime state.
    public static final int[] switchlist = new int[MAXSWITCHES * 2];
    public static int numswitches;
    public static final Button[] buttonlist = new Button[MAXBUTTONS];

    static {
        for (int i = 0; i < MAXBUTTONS; i++) {
            buttonlist[i] = new Button();
        }
    }

    // p_switch.c lines 107–148 — P_InitSwitchList.
    public static void pInitSwitchList() {
        int episode = 1;

        if (Doomstat.gamemode == Doomstat.GameMode.registered) {
            episode = 2;
        } else if (Doomstat.gamemode == Doomstat.GameMode.commercial) {
            episode = 3;
        }

        int index = 0;
        for (int i = 0; i < MAXSWITCHES; i++) {
            if (alphSwitchList[i].episode == 0) {
                numswitches = index / 2;
                switchlist[index] = -1;
                break;
            }

            if (alphSwitchList[i].episode <= episode) {
                switchlist[index++] = RData.rTextureNumForName(alphSwitchList[i].name1);
                switchlist[index++] = RData.rTextureNumForName(alphSwitchList[i].name2);
            }
        }
    }

    // p_switch.c lines 154–190 — P_StartButton.
    public static void pStartButton(Line line, int w, int texture, int time) {
        // See if button is already pressed.
        for (int i = 0; i < MAXBUTTONS; i++) {
            if (buttonlist[i].btimer != 0
                    && buttonlist[i].line == line) {
                return;
            }
        }

        for (int i = 0; i < MAXBUTTONS; i++) {
            if (buttonlist[i].btimer == 0) {
                buttonlist[i].line = line;
                buttonlist[i].where = w;
                buttonlist[i].btexture = texture;
                buttonlist[i].btimer = time;
                buttonlist[i].soundorg = line.frontsector.soundorg;
                return;
            }
        }

        throw ISystem.iError("P_StartButton: no button slots left!");
    }

    // p_switch.c lines 200–263 — P_ChangeSwitchTexture.
    // Preserves the C quirk `S_StartSound(buttonlist->soundorg, ...)`
    // — `buttonlist` decays to `&buttonlist[0]`, so the SFX always
    // plays at slot 0's stored soundorg rather than at the switch
    // being pressed.
    public static void pChangeSwitchTexture(Line line, int useAgain) {
        if (useAgain == 0) {
            line.special = 0;
        }

        int texTop = PSetup.sides[line.sidenum[0]].toptexture;
        int texMid = PSetup.sides[line.sidenum[0]].midtexture;
        int texBot = PSetup.sides[line.sidenum[0]].bottomtexture;

        int sound = Sounds.sfx_swtchn;

        // EXIT SWITCH?
        if (line.special == 11) {
            sound = Sounds.sfx_swtchx;
        }

        for (int i = 0; i < numswitches * 2; i++) {
            if (switchlist[i] == texTop) {
                SSound.sStartSound(buttonlist[0].soundorg, sound);
                PSetup.sides[line.sidenum[0]].toptexture = switchlist[i ^ 1];

                if (useAgain != 0) {
                    pStartButton(line, top, switchlist[i], BUTTONTIME);
                }
                return;
            } else {
                if (switchlist[i] == texMid) {
                    SSound.sStartSound(buttonlist[0].soundorg, sound);
                    PSetup.sides[line.sidenum[0]].midtexture = switchlist[i ^ 1];

                    if (useAgain != 0) {
                        pStartButton(line, middle, switchlist[i], BUTTONTIME);
                    }
                    return;
                } else {
                    if (switchlist[i] == texBot) {
                        SSound.sStartSound(buttonlist[0].soundorg, sound);
                        PSetup.sides[line.sidenum[0]].bottomtexture = switchlist[i ^ 1];

                        if (useAgain != 0) {
                            pStartButton(line, bottom, switchlist[i], BUTTONTIME);
                        }
                        return;
                    }
                }
            }
        }
    }

    //
    // P_UseSpecialLine
    // Called when a thing uses a special line.
    // Only the front sides of lines are usable.
    //
    // p_switch.c lines 274–653 — line-special "use" dispatcher.
    // Returns true on dispatch, false when the side/special combo is
    // a no-op for this caller. The C structure is preserved verbatim
    // — three guard switches (back-side, monster-allowed, then the
    // big dispatch) followed by a flat `return true`.
    public static boolean pUseSpecialLine(Mobj thing, Line line, int side) {
        // Err...
        // Use the back sides of VERY SPECIAL lines...
        if (side != 0) {
            switch (line.special) {
                case 124:
                    // Sliding door open&close
                    // UNUSED?
                    break;

                default:
                    return false;
            }
        }

        // Switches that other things can activate.
        if (thing.player == null) {
            // never open secret doors
            if ((line.flags & Line.ML_SECRET) != 0) {
                return false;
            }

            switch (line.special) {
                case 1:   // MANUAL DOOR RAISE
                case 32:  // MANUAL BLUE
                case 33:  // MANUAL RED
                case 34:  // MANUAL YELLOW
                    break;

                default:
                    return false;
            }
        }

        // do something
        switch (line.special) {
            // MANUALS
            case 1:    // Vertical Door
            case 26:   // Blue Door/Locked
            case 27:   // Yellow Door /Locked
            case 28:   // Red Door /Locked

            case 31:   // Manual door open
            case 32:   // Blue locked door open
            case 33:   // Red locked door open
            case 34:   // Yellow locked door open

            case 117:  // Blazing door raise
            case 118:  // Blazing door open
                PDoors.evVerticalDoor(line, thing);
                break;

            //UNUSED - Door Slide Open&Close
            // case 124:
            // EV_SlidingDoor (line, thing);
            // break;

            // SWITCHES
            case 7:
                // Build Stairs
                if (PFloor.evBuildStairs(line, PFloor.build8) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 9:
                // Change Donut
                if (PSpec.evDoDonut(line) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 11:
                // Exit level
                pChangeSwitchTexture(line, 0);
                doom.system.GGame.gExitLevel();
                break;

            case 14:
                // Raise Floor 32 and change texture
                if (PPlats.evDoPlat(line, PPlats.raiseAndChange, 32) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 15:
                // Raise Floor 24 and change texture
                if (PPlats.evDoPlat(line, PPlats.raiseAndChange, 24) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 18:
                // Raise Floor to next highest floor
                if (PFloor.evDoFloor(line, PFloor.raiseFloorToNearest) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 20:
                // Raise Plat next highest floor and change texture
                if (PPlats.evDoPlat(line, PPlats.raiseToNearestAndChange, 0) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 21:
                // PlatDownWaitUpStay
                if (PPlats.evDoPlat(line, PPlats.downWaitUpStay, 0) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 23:
                // Lower Floor to Lowest
                if (PFloor.evDoFloor(line, PFloor.lowerFloorToLowest) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 29:
                // Raise Door
                if (PDoors.evDoDoor(line, PDoors.normal) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 41:
                // Lower Ceiling to Floor
                if (PCeilng.evDoCeiling(line, PCeilng.lowerToFloor) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 71:
                // Turbo Lower Floor
                if (PFloor.evDoFloor(line, PFloor.turboLower) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 49:
                // Ceiling Crush And Raise
                if (PCeilng.evDoCeiling(line, PCeilng.crushAndRaise) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 50:
                // Close Door
                if (PDoors.evDoDoor(line, PDoors.close) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 51:
                // Secret EXIT
                pChangeSwitchTexture(line, 0);
                doom.system.GGame.gSecretExitLevel();
                break;

            case 55:
                // Raise Floor Crush
                if (PFloor.evDoFloor(line, PFloor.raiseFloorCrush) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 101:
                // Raise Floor
                if (PFloor.evDoFloor(line, PFloor.raiseFloor) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 102:
                // Lower Floor to Surrounding floor height
                if (PFloor.evDoFloor(line, PFloor.lowerFloor) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 103:
                // Open Door
                if (PDoors.evDoDoor(line, PDoors.open) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 111:
                // Blazing Door Raise (faster than TURBO!)
                if (PDoors.evDoDoor(line, PDoors.blazeRaise) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 112:
                // Blazing Door Open (faster than TURBO!)
                if (PDoors.evDoDoor(line, PDoors.blazeOpen) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 113:
                // Blazing Door Close (faster than TURBO!)
                if (PDoors.evDoDoor(line, PDoors.blazeClose) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 122:
                // Blazing PlatDownWaitUpStay
                if (PPlats.evDoPlat(line, PPlats.blazeDWUS, 0) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 127:
                // Build Stairs Turbo 16
                if (PFloor.evBuildStairs(line, PFloor.turbo16) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 131:
                // Raise Floor Turbo
                if (PFloor.evDoFloor(line, PFloor.raiseFloorTurbo) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 133:
                // BlzOpenDoor BLUE
            case 135:
                // BlzOpenDoor RED
            case 137:
                // BlzOpenDoor YELLOW
                if (PDoors.evDoLockedDoor(line, PDoors.blazeOpen, thing) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            case 140:
                // Raise Floor 512
                if (PFloor.evDoFloor(line, PFloor.raiseFloor512) != 0) {
                    pChangeSwitchTexture(line, 0);
                }
                break;

            // BUTTONS
            case 42:
                // Close Door
                if (PDoors.evDoDoor(line, PDoors.close) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 43:
                // Lower Ceiling to Floor
                if (PCeilng.evDoCeiling(line, PCeilng.lowerToFloor) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 45:
                // Lower Floor to Surrounding floor height
                if (PFloor.evDoFloor(line, PFloor.lowerFloor) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 60:
                // Lower Floor to Lowest
                if (PFloor.evDoFloor(line, PFloor.lowerFloorToLowest) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 61:
                // Open Door
                if (PDoors.evDoDoor(line, PDoors.open) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 62:
                // PlatDownWaitUpStay
                if (PPlats.evDoPlat(line, PPlats.downWaitUpStay, 1) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 63:
                // Raise Door
                if (PDoors.evDoDoor(line, PDoors.normal) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 64:
                // Raise Floor to ceiling
                if (PFloor.evDoFloor(line, PFloor.raiseFloor) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 66:
                // Raise Floor 24 and change texture
                if (PPlats.evDoPlat(line, PPlats.raiseAndChange, 24) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 67:
                // Raise Floor 32 and change texture
                if (PPlats.evDoPlat(line, PPlats.raiseAndChange, 32) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 65:
                // Raise Floor Crush
                if (PFloor.evDoFloor(line, PFloor.raiseFloorCrush) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 68:
                // Raise Plat to next highest floor and change texture
                if (PPlats.evDoPlat(line, PPlats.raiseToNearestAndChange, 0) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 69:
                // Raise Floor to next highest floor
                if (PFloor.evDoFloor(line, PFloor.raiseFloorToNearest) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 70:
                // Turbo Lower Floor
                if (PFloor.evDoFloor(line, PFloor.turboLower) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 114:
                // Blazing Door Raise (faster than TURBO!)
                if (PDoors.evDoDoor(line, PDoors.blazeRaise) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 115:
                // Blazing Door Open (faster than TURBO!)
                if (PDoors.evDoDoor(line, PDoors.blazeOpen) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 116:
                // Blazing Door Close (faster than TURBO!)
                if (PDoors.evDoDoor(line, PDoors.blazeClose) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 123:
                // Blazing PlatDownWaitUpStay
                if (PPlats.evDoPlat(line, PPlats.blazeDWUS, 0) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 132:
                // Raise Floor Turbo
                if (PFloor.evDoFloor(line, PFloor.raiseFloorTurbo) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 99:
                // BlzOpenDoor BLUE
            case 134:
                // BlzOpenDoor RED
            case 136:
                // BlzOpenDoor YELLOW
                if (PDoors.evDoLockedDoor(line, PDoors.blazeOpen, thing) != 0) {
                    pChangeSwitchTexture(line, 1);
                }
                break;

            case 138:
                // Light Turn On
                PLights.evLightTurnOn(line, 255);
                pChangeSwitchTexture(line, 1);
                break;

            case 139:
                // Light Turn Off
                PLights.evLightTurnOn(line, 35);
                pChangeSwitchTexture(line, 1);
                break;
        }

        return true;
    }

    private PSwitch() {
    }
}
