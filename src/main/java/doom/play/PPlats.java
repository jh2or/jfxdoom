// Port of linuxdoom-1.10/p_plats.c — platform (elevator) animations.
//
// Five plat types (perpetualRaise, downWaitUpStay, raiseAndChange,
// raiseToNearestAndChange, blazeDWUS) drive the single T_PlatRaise
// thinker. State machine: `up` / `down` / `waiting` / `in_stasis`,
// counted in tics via `count`.
//
// activeplats[MAXPLATS] — the fixed-size slot table EV_StopPlat and
// P_ActivateInStasis iterate. P_AddActivePlat calls I_Error if the
// table is full (CLAUDE.md §2: preserve the C's fail-hard contract).
//
// Pause sentinel: the C sets `thinker.function.acv = NULL` in
// EV_StopPlat — same translation as PCeilng (deviation 22): writing
// `acp1 = null` in Java parks the thinker so P_RunThinkers skips it.
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.ISystem;

public final class PPlats {

    // p_spec.h lines 304–306 — timing constants.
    public static final int PLATWAIT = 3;
    public static final int PLATSPEED = Fixed.FRACUNIT;
    public static final int MAXPLATS = 30;

    // p_spec.h lines 262–269 — plat_e.
    public static final int up        = 0;
    public static final int down      = 1;
    public static final int waiting   = 2;
    public static final int in_stasis = 3;

    // p_spec.h lines 273–281 — plattype_e.
    public static final int perpetualRaise         = 0;
    public static final int downWaitUpStay         = 1;
    public static final int raiseAndChange         = 2;
    public static final int raiseToNearestAndChange = 3;
    public static final int blazeDWUS              = 4;

    // p_spec.h lines 285–300 — plat_t.
    public static final class Plat extends Thinker {
        public Sector sector;
        public int speed;           // fixed_t
        public int low;             // fixed_t
        public int high;            // fixed_t
        public int wait;
        public int count;
        public int status;          // plat_e
        public int oldstatus;       // plat_e
        public boolean crush;
        public int tag;
        public int type;            // plattype_e
    }

    // p_plats.c line 45 — the active-plat slot table.
    public static final Plat[] activeplats = new Plat[MAXPLATS];

    // p_plats.c lines 52–131 — T_PlatRaise.
    public static void tPlatRaise(Thinker t) {
        Plat plat = (Plat) t;
        PSpec.Result res;

        switch (plat.status) {
            case up:
                res = PFloor.tMovePlane(plat.sector, plat.speed,
                        plat.high, plat.crush, 0, 1);

                if (plat.type == raiseAndChange
                        || plat.type == raiseToNearestAndChange) {
                    if ((PTick.leveltime & 7) == 0) {
                        SSound.sStartSound(plat.sector.soundorg, Sounds.sfx_stnmov);
                    }
                }

                if (res == PSpec.Result.crushed && !plat.crush) {
                    plat.count = plat.wait;
                    plat.status = down;
                    SSound.sStartSound(plat.sector.soundorg, Sounds.sfx_pstart);
                } else {
                    if (res == PSpec.Result.pastdest) {
                        plat.count = plat.wait;
                        plat.status = waiting;
                        SSound.sStartSound(plat.sector.soundorg, Sounds.sfx_pstop);

                        switch (plat.type) {
                            case blazeDWUS:
                            case downWaitUpStay:
                                pRemoveActivePlat(plat);
                                break;

                            case raiseAndChange:
                            case raiseToNearestAndChange:
                                pRemoveActivePlat(plat);
                                break;

                            default:
                                break;
                        }
                    }
                }
                break;

            case down:
                res = PFloor.tMovePlane(plat.sector, plat.speed,
                        plat.low, false, 0, -1);

                if (res == PSpec.Result.pastdest) {
                    plat.count = plat.wait;
                    plat.status = waiting;
                    SSound.sStartSound(plat.sector.soundorg, Sounds.sfx_pstop);
                }
                break;

            case waiting:
                if (--plat.count == 0) {
                    if (plat.sector.floorheight == plat.low) {
                        plat.status = up;
                    } else {
                        plat.status = down;
                    }
                    SSound.sStartSound(plat.sector.soundorg, Sounds.sfx_pstart);
                }
                // C falls through to in_stasis — both just break.
            case in_stasis:
                break;
        }
    }

    // p_plats.c lines 138–254 — EV_DoPlat.
    // `amount` is only read by raiseAndChange.
    public static int evDoPlat(Line line, int type, int amount) {
        int secnum = -1;
        int rtn = 0;

        // Activate all <type> plats that are in_stasis
        switch (type) {
            case perpetualRaise:
                pActivateInStasis(line.tag);
                break;
            default:
                break;
        }

        while ((secnum = PSpec.pFindSectorFromLineTag(line, secnum)) >= 0) {
            Sector sec = PSetup.sectors[secnum];

            if (sec.specialdata != null) {
                continue;
            }

            // Find lowest & highest floors around sector
            rtn = 1;
            Plat plat = new Plat();
            PTick.pAddThinker(plat);

            plat.type = type;
            plat.sector = sec;
            plat.sector.specialdata = plat;
            plat.function.acp1 = PPlats::tPlatRaise;
            plat.crush = false;
            plat.tag = line.tag;

            switch (type) {
                case raiseToNearestAndChange:
                    plat.speed = PLATSPEED / 2;
                    sec.floorpic = PSetup.sides[line.sidenum[0]].sector.floorpic;
                    plat.high = PSpec.pFindNextHighestFloor(sec, sec.floorheight);
                    plat.wait = 0;
                    plat.status = up;
                    // NO MORE DAMAGE, IF APPLICABLE
                    sec.special = 0;

                    SSound.sStartSound(sec.soundorg, Sounds.sfx_stnmov);
                    break;

                case raiseAndChange:
                    plat.speed = PLATSPEED / 2;
                    sec.floorpic = PSetup.sides[line.sidenum[0]].sector.floorpic;
                    plat.high = sec.floorheight + amount * Fixed.FRACUNIT;
                    plat.wait = 0;
                    plat.status = up;

                    SSound.sStartSound(sec.soundorg, Sounds.sfx_stnmov);
                    break;

                case downWaitUpStay:
                    plat.speed = PLATSPEED * 4;
                    plat.low = PSpec.pFindLowestFloorSurrounding(sec);

                    if (plat.low > sec.floorheight) {
                        plat.low = sec.floorheight;
                    }

                    plat.high = sec.floorheight;
                    plat.wait = 35 * PLATWAIT;
                    plat.status = down;
                    SSound.sStartSound(sec.soundorg, Sounds.sfx_pstart);
                    break;

                case blazeDWUS:
                    plat.speed = PLATSPEED * 8;
                    plat.low = PSpec.pFindLowestFloorSurrounding(sec);

                    if (plat.low > sec.floorheight) {
                        plat.low = sec.floorheight;
                    }

                    plat.high = sec.floorheight;
                    plat.wait = 35 * PLATWAIT;
                    plat.status = down;
                    SSound.sStartSound(sec.soundorg, Sounds.sfx_pstart);
                    break;

                case perpetualRaise:
                    plat.speed = PLATSPEED;
                    plat.low = PSpec.pFindLowestFloorSurrounding(sec);

                    if (plat.low > sec.floorheight) {
                        plat.low = sec.floorheight;
                    }

                    plat.high = PSpec.pFindHighestFloorSurrounding(sec);

                    if (plat.high < sec.floorheight) {
                        plat.high = sec.floorheight;
                    }

                    plat.wait = 35 * PLATWAIT;
                    plat.status = MRandom.pRandom() & 1;

                    SSound.sStartSound(sec.soundorg, Sounds.sfx_pstart);
                    break;
            }
            pAddActivePlat(plat);
        }
        return rtn;
    }

    // p_plats.c lines 258–271 — P_ActivateInStasis.
    public static void pActivateInStasis(int tag) {
        for (int i = 0; i < MAXPLATS; i++) {
            if (activeplats[i] != null
                    && activeplats[i].tag == tag
                    && activeplats[i].status == in_stasis) {
                activeplats[i].status = activeplats[i].oldstatus;
                activeplats[i].function.acp1 = PPlats::tPlatRaise;
            }
        }
    }

    // p_plats.c lines 273–286 — EV_StopPlat.
    public static void evStopPlat(Line line) {
        for (int j = 0; j < MAXPLATS; j++) {
            if (activeplats[j] != null
                    && activeplats[j].status != in_stasis
                    && activeplats[j].tag == line.tag) {
                activeplats[j].oldstatus = activeplats[j].status;
                activeplats[j].status = in_stasis;
                // C: function.acv = (actionf_v)NULL; — parks the thinker.
                activeplats[j].function.acp1 = null;
            }
        }
    }

    // p_plats.c lines 288–299 — P_AddActivePlat.
    public static void pAddActivePlat(Plat plat) {
        for (int i = 0; i < MAXPLATS; i++) {
            if (activeplats[i] == null) {
                activeplats[i] = plat;
                return;
            }
        }
        throw ISystem.iError("P_AddActivePlat: no more plats!");
    }

    // p_plats.c lines 301–314 — P_RemoveActivePlat.
    public static void pRemoveActivePlat(Plat plat) {
        for (int i = 0; i < MAXPLATS; i++) {
            if (plat == activeplats[i]) {
                activeplats[i].sector.specialdata = null;
                PTick.pRemoveThinker(activeplats[i]);
                activeplats[i] = null;
                return;
            }
        }
        throw ISystem.iError("P_RemoveActivePlat: can't find plat!");
    }

    private PPlats() {
    }
}
