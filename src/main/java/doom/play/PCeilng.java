// Port of linuxdoom-1.10/p_ceilng.c — ceiling animations.
//
// Six ceiling types (lower-to-floor, raise-to-highest, lower-and-
// crush, crush-and-raise, fast-crush-and-raise, silent-crush-and-
// raise) drive the single T_MoveCeiling thinker. Active ceilings are
// tracked in a fixed-size `activeceilings[MAXCEILINGS]` slot table so
// EV_CeilingCrushStop and P_ActivateInStasisCeiling can iterate.
//
// Pause/resume pattern: the C sets `thinker.function.acv = NULL` to
// pause a ceiling (still in the thinker list, but the union storage
// is clobbered so P_RunThinkers skips it) and re-assigns
// `acp1 = T_MoveCeiling` to resume. Our Actionf holds separate fields
// per arity, so writing `acp1 = null` (pause) / `acp1 = ::tMoveCeiling`
// (resume) maps to the same semantics — see deviation 14 for the
// broader union-to-separate-fields decision.
package doom.play;

import doom.render.Line;
import doom.render.Sector;
import doom.fixed.Fixed;
import doom.sound.SSound;
import doom.sound.Sounds;

public final class PCeilng {

    // p_spec.h lines 516–518 — timing constants.
    public static final int CEILSPEED = Fixed.FRACUNIT;
    public static final int CEILWAIT  = 150;
    public static final int MAXCEILINGS = 30;

    // p_spec.h lines 480–489 — ceiling_e.
    // Kept as int constants rather than Java enum since the switch
    // statements in T_MoveCeiling rely on C's ordinal semantics and
    // the fall-through `case` labels translate more literally.
    public static final int lowerToFloor        = 0;
    public static final int raiseToHighest      = 1;
    public static final int lowerAndCrush       = 2;
    public static final int crushAndRaise       = 3;
    public static final int fastCrushAndRaise   = 4;
    public static final int silentCrushAndRaise = 5;

    // p_spec.h lines 493–510 — ceiling_t.
    public static final class Ceiling extends Thinker {
        public int type;
        public Sector sector;
        public int bottomheight;
        public int topheight;
        public int speed;
        public boolean crush;
        public int direction;     // 1 = up, 0 = waiting, -1 = down
        public int tag;
        public int olddirection;
    }

    // p_ceilng.c line 45 — the active-ceiling slot table.
    public static final Ceiling[] activeceilings = new Ceiling[MAXCEILINGS];

    // p_ceilng.c lines 52–164 — T_MoveCeiling.
    public static void tMoveCeiling(Thinker t) {
        Ceiling ceiling = (Ceiling) t;
        PSpec.Result res;

        switch (ceiling.direction) {
            case 0:
                // IN STASIS
                break;
            case 1:
                // UP
                res = PFloor.tMovePlane(ceiling.sector, ceiling.speed,
                        ceiling.topheight, false, 1, ceiling.direction);

                if ((PTick.leveltime & 7) == 0) {
                    switch (ceiling.type) {
                        case silentCrushAndRaise:
                            break;
                        default:
                            SSound.sStartSound(ceiling.sector.soundorg, Sounds.sfx_stnmov);
                            // ?
                            break;
                    }
                }

                if (res == PSpec.Result.pastdest) {
                    switch (ceiling.type) {
                        case raiseToHighest:
                            pRemoveActiveCeiling(ceiling);
                            break;

                        case silentCrushAndRaise:
                            SSound.sStartSound(ceiling.sector.soundorg, Sounds.sfx_pstop);
                            // fall through
                        case fastCrushAndRaise:
                        case crushAndRaise:
                            ceiling.direction = -1;
                            break;

                        default:
                            break;
                    }
                }
                break;

            case -1:
                // DOWN
                res = PFloor.tMovePlane(ceiling.sector, ceiling.speed,
                        ceiling.bottomheight, ceiling.crush, 1, ceiling.direction);

                if ((PTick.leveltime & 7) == 0) {
                    switch (ceiling.type) {
                        case silentCrushAndRaise:
                            break;
                        default:
                            SSound.sStartSound(ceiling.sector.soundorg, Sounds.sfx_stnmov);
                    }
                }

                if (res == PSpec.Result.pastdest) {
                    switch (ceiling.type) {
                        case silentCrushAndRaise:
                            SSound.sStartSound(ceiling.sector.soundorg, Sounds.sfx_pstop);
                            // fall through
                        case crushAndRaise:
                            ceiling.speed = CEILSPEED;
                            // fall through
                        case fastCrushAndRaise:
                            ceiling.direction = 1;
                            break;

                        case lowerAndCrush:
                        case lowerToFloor:
                            pRemoveActiveCeiling(ceiling);
                            break;

                        default:
                            break;
                    }
                } else { // ( res != pastdest )
                    if (res == PSpec.Result.crushed) {
                        switch (ceiling.type) {
                            case silentCrushAndRaise:
                            case crushAndRaise:
                            case lowerAndCrush:
                                ceiling.speed = CEILSPEED / 8;
                                break;

                            default:
                                break;
                        }
                    }
                }
                break;
        }
    }

    // p_ceilng.c lines 171–245 — EV_DoCeiling.
    public static int evDoCeiling(Line line, int type) {
        int secnum = -1;
        int rtn = 0;

        // Reactivate in-stasis ceilings...for certain types.
        switch (type) {
            case fastCrushAndRaise:
            case silentCrushAndRaise:
            case crushAndRaise:
                pActivateInStasisCeiling(line);
                // fall through — C has no break here.
            default:
                break;
        }

        while ((secnum = PSpec.pFindSectorFromLineTag(line, secnum)) >= 0) {
            Sector sec = PSetup.sectors[secnum];
            if (sec.specialdata != null) {
                continue;
            }

            // new door thinker
            rtn = 1;
            Ceiling ceiling = new Ceiling();
            PTick.pAddThinker(ceiling);
            sec.specialdata = ceiling;
            ceiling.function.acp1 = PCeilng::tMoveCeiling;
            ceiling.sector = sec;
            ceiling.crush = false;

            switch (type) {
                case fastCrushAndRaise:
                    ceiling.crush = true;
                    ceiling.topheight = sec.ceilingheight;
                    ceiling.bottomheight = sec.floorheight + (8 * Fixed.FRACUNIT);
                    ceiling.direction = -1;
                    ceiling.speed = CEILSPEED * 2;
                    break;

                case silentCrushAndRaise:
                case crushAndRaise:
                    ceiling.crush = true;
                    ceiling.topheight = sec.ceilingheight;
                    // fall through
                case lowerAndCrush:
                case lowerToFloor:
                    ceiling.bottomheight = sec.floorheight;
                    if (type != lowerToFloor) {
                        ceiling.bottomheight += 8 * Fixed.FRACUNIT;
                    }
                    ceiling.direction = -1;
                    ceiling.speed = CEILSPEED;
                    break;

                case raiseToHighest:
                    ceiling.topheight = PSpec.pFindHighestCeilingSurrounding(sec);
                    ceiling.direction = 1;
                    ceiling.speed = CEILSPEED;
                    break;
            }

            ceiling.tag = sec.tag;
            ceiling.type = type;
            pAddActiveCeiling(ceiling);
        }
        return rtn;
    }

    // p_ceilng.c lines 251–263 — P_AddActiveCeiling.
    public static void pAddActiveCeiling(Ceiling c) {
        for (int i = 0; i < MAXCEILINGS; i++) {
            if (activeceilings[i] == null) {
                activeceilings[i] = c;
                return;
            }
        }
    }

    // p_ceilng.c lines 270–284 — P_RemoveActiveCeiling.
    public static void pRemoveActiveCeiling(Ceiling c) {
        for (int i = 0; i < MAXCEILINGS; i++) {
            if (activeceilings[i] == c) {
                activeceilings[i].sector.specialdata = null;
                PTick.pRemoveThinker(activeceilings[i]);
                activeceilings[i] = null;
                break;
            }
        }
    }

    // p_ceilng.c lines 291–306 — P_ActivateInStasisCeiling.
    public static void pActivateInStasisCeiling(Line line) {
        for (int i = 0; i < MAXCEILINGS; i++) {
            if (activeceilings[i] != null
                    && activeceilings[i].tag == line.tag
                    && activeceilings[i].direction == 0) {
                activeceilings[i].direction = activeceilings[i].olddirection;
                activeceilings[i].function.acp1 = PCeilng::tMoveCeiling;
            }
        }
    }

    // p_ceilng.c lines 314–335 — EV_CeilingCrushStop.
    public static int evCeilingCrushStop(Line line) {
        int rtn = 0;
        for (int i = 0; i < MAXCEILINGS; i++) {
            if (activeceilings[i] != null
                    && activeceilings[i].tag == line.tag
                    && activeceilings[i].direction != 0) {
                activeceilings[i].olddirection = activeceilings[i].direction;
                // C: function.acv = (actionf_v)NULL;
                // Our Actionf has separate fields per arity — clear acp1
                // since pRunThinkers keys off it (deviation 14).
                activeceilings[i].function.acp1 = null;
                activeceilings[i].direction = 0; // in-stasis
                rtn = 1;
            }
        }
        return rtn;
    }

    private PCeilng() {
    }
}
