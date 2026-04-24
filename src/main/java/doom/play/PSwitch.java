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

    private PSwitch() {
    }
}
