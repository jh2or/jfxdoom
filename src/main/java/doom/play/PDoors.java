// Port of linuxdoom-1.10/p_doors.c — partial.
//
// Carries T_VerticalDoor (the door thinker), EV_DoDoor (tag-matched
// door spawn), and the two deferred-spawn helpers P_SpawnDoorCloseIn30
// and P_SpawnDoorRaiseIn5Mins.
//
// EV_DoLockedDoor and EV_VerticalDoor both reach into `mobj->player`
// and `player->cards[]` (+ PD_BLUEO/PD_REDO/... message strings) to
// enforce keycard locks — Player.cards is still a stub, so those two
// land when the player inventory does.
//
// The abandoned `#if 0` sliding-door block at the bottom of p_doors.c
// is intentionally skipped (CLAUDE.md §2 — dead code stays dead).
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.sound.SSound;
import doom.sound.Sounds;

public final class PDoors {

    // p_spec.h lines 364–365 — timing constants.
    public static final int VDOORSPEED = Fixed.FRACUNIT * 2;
    public static final int VDOORWAIT  = 150;

    // p_spec.h lines 328–339 — vldoor_e.
    // Int constants rather than a Java enum, same rationale as
    // PCeilng/PPlats: the switch fall-through labels translate
    // literally.
    public static final int normal          = 0;
    public static final int close30ThenOpen = 1;
    public static final int close           = 2;
    public static final int open            = 3;
    public static final int raiseIn5Mins    = 4;
    public static final int blazeRaise      = 5;
    public static final int blazeOpen       = 6;
    public static final int blazeClose      = 7;

    // p_spec.h lines 343–360 — vldoor_t.
    public static final class VLDoor extends Thinker {
        public int type;
        public Sector sector;
        public int topheight;       // fixed_t
        public int speed;           // fixed_t
        public int direction;       // 1 = up, 0 = waiting at top, -1 = down, 2 = initial wait
        public int topwait;
        public int topcountdown;
    }

    // p_doors.c lines 63–198 — T_VerticalDoor.
    public static void tVerticalDoor(Thinker t) {
        VLDoor door = (VLDoor) t;
        PSpec.Result res;

        switch (door.direction) {
            case 0:
                // WAITING
                if (--door.topcountdown == 0) {
                    switch (door.type) {
                        case blazeRaise:
                            door.direction = -1; // time to go back down
                            SSound.sStartSound(door.sector.soundorg, Sounds.sfx_bdcls);
                            break;

                        case normal:
                            door.direction = -1; // time to go back down
                            SSound.sStartSound(door.sector.soundorg, Sounds.sfx_dorcls);
                            break;

                        case close30ThenOpen:
                            door.direction = 1;
                            SSound.sStartSound(door.sector.soundorg, Sounds.sfx_doropn);
                            break;

                        default:
                            break;
                    }
                }
                break;

            case 2:
                // INITIAL WAIT
                if (--door.topcountdown == 0) {
                    switch (door.type) {
                        case raiseIn5Mins:
                            door.direction = 1;
                            door.type = normal;
                            SSound.sStartSound(door.sector.soundorg, Sounds.sfx_doropn);
                            break;

                        default:
                            break;
                    }
                }
                break;

            case -1:
                // DOWN
                res = PFloor.tMovePlane(door.sector, door.speed,
                        door.sector.floorheight, false, 1, door.direction);
                if (res == PSpec.Result.pastdest) {
                    switch (door.type) {
                        case blazeRaise:
                        case blazeClose:
                            door.sector.specialdata = null;
                            PTick.pRemoveThinker(door); // unlink and free
                            SSound.sStartSound(door.sector.soundorg, Sounds.sfx_bdcls);
                            break;

                        case normal:
                        case close:
                            door.sector.specialdata = null;
                            PTick.pRemoveThinker(door); // unlink and free
                            break;

                        case close30ThenOpen:
                            door.direction = 0;
                            door.topcountdown = 35 * 30;
                            break;

                        default:
                            break;
                    }
                } else if (res == PSpec.Result.crushed) {
                    switch (door.type) {
                        case blazeClose:
                        case close: // DO NOT GO BACK UP!
                            break;

                        default:
                            door.direction = 1;
                            SSound.sStartSound(door.sector.soundorg, Sounds.sfx_doropn);
                            break;
                    }
                }
                break;

            case 1:
                // UP
                res = PFloor.tMovePlane(door.sector, door.speed,
                        door.topheight, false, 1, door.direction);

                if (res == PSpec.Result.pastdest) {
                    switch (door.type) {
                        case blazeRaise:
                        case normal:
                            door.direction = 0; // wait at top
                            door.topcountdown = door.topwait;
                            break;

                        case close30ThenOpen:
                        case blazeOpen:
                        case open:
                            door.sector.specialdata = null;
                            PTick.pRemoveThinker(door); // unlink and free
                            break;

                        default:
                            break;
                    }
                }
                break;
        }
    }

    // p_doors.c lines 263–347 — EV_DoDoor.
    public static int evDoDoor(Line line, int type) {
        int secnum = -1;
        int rtn = 0;

        while ((secnum = PSpec.pFindSectorFromLineTag(line, secnum)) >= 0) {
            Sector sec = PSetup.sectors[secnum];
            if (sec.specialdata != null) {
                continue;
            }

            // new door thinker
            rtn = 1;
            VLDoor door = new VLDoor();
            PTick.pAddThinker(door);
            sec.specialdata = door;

            door.function.acp1 = PDoors::tVerticalDoor;
            door.sector = sec;
            door.type = type;
            door.topwait = VDOORWAIT;
            door.speed = VDOORSPEED;

            switch (type) {
                case blazeClose:
                    door.topheight = PSpec.pFindLowestCeilingSurrounding(sec);
                    door.topheight -= 4 * Fixed.FRACUNIT;
                    door.direction = -1;
                    door.speed = VDOORSPEED * 4;
                    SSound.sStartSound(door.sector.soundorg, Sounds.sfx_bdcls);
                    break;

                case close:
                    door.topheight = PSpec.pFindLowestCeilingSurrounding(sec);
                    door.topheight -= 4 * Fixed.FRACUNIT;
                    door.direction = -1;
                    SSound.sStartSound(door.sector.soundorg, Sounds.sfx_dorcls);
                    break;

                case close30ThenOpen:
                    door.topheight = sec.ceilingheight;
                    door.direction = -1;
                    SSound.sStartSound(door.sector.soundorg, Sounds.sfx_dorcls);
                    break;

                case blazeRaise:
                case blazeOpen:
                    door.direction = 1;
                    door.topheight = PSpec.pFindLowestCeilingSurrounding(sec);
                    door.topheight -= 4 * Fixed.FRACUNIT;
                    door.speed = VDOORSPEED * 4;
                    if (door.topheight != sec.ceilingheight) {
                        SSound.sStartSound(door.sector.soundorg, Sounds.sfx_bdopn);
                    }
                    break;

                case normal:
                case open:
                    door.direction = 1;
                    door.topheight = PSpec.pFindLowestCeilingSurrounding(sec);
                    door.topheight -= 4 * Fixed.FRACUNIT;
                    if (door.topheight != sec.ceilingheight) {
                        SSound.sStartSound(door.sector.soundorg, Sounds.sfx_doropn);
                    }
                    break;

                default:
                    break;
            }
        }
        return rtn;
    }

    // p_doors.c lines 505–522 — P_SpawnDoorCloseIn30.
    public static void pSpawnDoorCloseIn30(Sector sec) {
        VLDoor door = new VLDoor();
        PTick.pAddThinker(door);

        sec.specialdata = door;
        sec.special = 0;

        door.function.acp1 = PDoors::tVerticalDoor;
        door.sector = sec;
        door.direction = 0;
        door.type = normal;
        door.speed = VDOORSPEED;
        door.topcountdown = 30 * 35;
    }

    // p_doors.c lines 527–550 — P_SpawnDoorRaiseIn5Mins.
    public static void pSpawnDoorRaiseIn5Mins(Sector sec, int secnum) {
        VLDoor door = new VLDoor();
        PTick.pAddThinker(door);

        sec.specialdata = door;
        sec.special = 0;

        door.function.acp1 = PDoors::tVerticalDoor;
        door.sector = sec;
        door.direction = 2;
        door.type = raiseIn5Mins;
        door.speed = VDOORSPEED;
        door.topheight = PSpec.pFindLowestCeilingSurrounding(sec);
        door.topheight -= 4 * Fixed.FRACUNIT;
        door.topwait = VDOORWAIT;
        door.topcountdown = 5 * 60 * 35;
    }

    private PDoors() {
    }
}
