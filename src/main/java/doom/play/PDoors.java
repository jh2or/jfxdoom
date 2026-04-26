// Port of linuxdoom-1.10/p_doors.c.
//
// Carries T_VerticalDoor (the door thinker), EV_DoDoor (tag-matched
// door spawn), EV_DoLockedDoor (keycard-gated EV_DoDoor), EV_VerticalDoor
// (manual "use" door — handles re-press toggling and lock checks), and
// the two deferred-spawn helpers P_SpawnDoorCloseIn30 and
// P_SpawnDoorRaiseIn5Mins.
//
// Locked-door checks: the C uses a NULL-origin S_StartSound for
// "wrong key" oof — the Java port passes null through SSound.sStartSound
// (which is a no-op stub anyway). When SSound is fleshed out, a null
// origin maps to "no positional source" — global play.
//
// The abandoned `#if 0` sliding-door block at the bottom of p_doors.c
// is intentionally skipped (CLAUDE.md §2 — dead code stays dead).
package doom.play;

import doom.fixed.Fixed;
import doom.render.Line;
import doom.render.Sector;
import doom.sound.SSound;
import doom.sound.Sounds;
import doom.system.Doomdef;
import doom.system.Dstrings;

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

    //
    // EV_DoLockedDoor — Move a locked door up/down.
    //
    // p_doors.c lines 206–260.
    public static int evDoLockedDoor(Line line, int type, Mobj thing) {
        Player p;

        p = thing.player;

        if (p == null) {
            return 0;
        }

        switch (line.special) {
            case 99:  // Blue Lock
            case 133:
                if (p == null) {
                    return 0;
                }
                if (!p.cards[Doomdef.it_bluecard] && !p.cards[Doomdef.it_blueskull]) {
                    p.message = Dstrings.PD_BLUEO;
                    SSound.sStartSound(null, Sounds.sfx_oof);
                    return 0;
                }
                break;

            case 134: // Red Lock
            case 135:
                if (p == null) {
                    return 0;
                }
                if (!p.cards[Doomdef.it_redcard] && !p.cards[Doomdef.it_redskull]) {
                    p.message = Dstrings.PD_REDO;
                    SSound.sStartSound(null, Sounds.sfx_oof);
                    return 0;
                }
                break;

            case 136: // Yellow Lock
            case 137:
                if (p == null) {
                    return 0;
                }
                if (!p.cards[Doomdef.it_yellowcard]
                        && !p.cards[Doomdef.it_yellowskull]) {
                    p.message = Dstrings.PD_YELLOWO;
                    SSound.sStartSound(null, Sounds.sfx_oof);
                    return 0;
                }
                break;
        }

        return evDoDoor(line, type);
    }

    //
    // EV_VerticalDoor — open a door manually, no tag value.
    //
    // p_doors.c lines 353–499.
    public static void evVerticalDoor(Line line, Mobj thing) {
        Player player;
        int secnum;
        Sector sec;
        VLDoor door;
        int side;

        side = 0; // only front sides can be used

        // Check for locks.
        player = thing.player;

        switch (line.special) {
            case 26: // Blue Lock
            case 32:
                if (player == null) {
                    return;
                }
                if (!player.cards[Doomdef.it_bluecard]
                        && !player.cards[Doomdef.it_blueskull]) {
                    player.message = Dstrings.PD_BLUEK;
                    SSound.sStartSound(null, Sounds.sfx_oof);
                    return;
                }
                break;

            case 27: // Yellow Lock
            case 34:
                if (player == null) {
                    return;
                }
                if (!player.cards[Doomdef.it_yellowcard]
                        && !player.cards[Doomdef.it_yellowskull]) {
                    player.message = Dstrings.PD_YELLOWK;
                    SSound.sStartSound(null, Sounds.sfx_oof);
                    return;
                }
                break;

            case 28: // Red Lock
            case 33:
                if (player == null) {
                    return;
                }
                if (!player.cards[Doomdef.it_redcard]
                        && !player.cards[Doomdef.it_redskull]) {
                    player.message = Dstrings.PD_REDK;
                    SSound.sStartSound(null, Sounds.sfx_oof);
                    return;
                }
                break;
        }

        // if the sector has an active thinker, use it
        sec = PSetup.sides[line.sidenum[side ^ 1]].sector;
        secnum = -1; // C: secnum = sec - sectors. Only used to compute door
                      // ownership/sector lookup; unused after this point.
        // The C declares secnum but only assigns it; nothing reads it
        // after this line either. We preserve the assignment to mirror
        // the C dead-store (CLAUDE.md §2: bug-faithful translation).

        if (sec.specialdata != null) {
            door = (VLDoor) sec.specialdata;
            switch (line.special) {
                case 1:  // ONLY FOR "RAISE" DOORS, NOT "OPEN"s
                case 26:
                case 27:
                case 28:
                case 117:
                    if (door.direction == -1) {
                        door.direction = 1; // go back up
                    } else {
                        if (thing.player == null) {
                            return; // JDC: bad guys never close doors
                        }

                        door.direction = -1; // start going down immediately
                    }
                    return;
            }
        }

        // for proper sound
        switch (line.special) {
            case 117: // BLAZING DOOR RAISE
            case 118: // BLAZING DOOR OPEN
                SSound.sStartSound(sec.soundorg, Sounds.sfx_bdopn);
                break;

            case 1:  // NORMAL DOOR SOUND
            case 31:
                SSound.sStartSound(sec.soundorg, Sounds.sfx_doropn);
                break;

            default: // LOCKED DOOR SOUND
                SSound.sStartSound(sec.soundorg, Sounds.sfx_doropn);
                break;
        }

        // new door thinker
        door = new VLDoor();
        PTick.pAddThinker(door);
        sec.specialdata = door;
        door.function.acp1 = PDoors::tVerticalDoor;
        door.sector = sec;
        door.direction = 1;
        door.speed = VDOORSPEED;
        door.topwait = VDOORWAIT;

        switch (line.special) {
            case 1:
            case 26:
            case 27:
            case 28:
                door.type = normal;
                break;

            case 31:
            case 32:
            case 33:
            case 34:
                door.type = open;
                line.special = 0;
                break;

            case 117: // blazing door raise
                door.type = blazeRaise;
                door.speed = VDOORSPEED * 4;
                break;
            case 118: // blazing door open
                door.type = blazeOpen;
                line.special = 0;
                door.speed = VDOORSPEED * 4;
                break;
        }

        // find the top and bottom of the movement range
        door.topheight = PSpec.pFindLowestCeilingSurrounding(sec);
        door.topheight -= 4 * Fixed.FRACUNIT;
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
